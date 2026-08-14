package org.lean4android.lsp

import org.lean4android.process.ProcessCommand
import org.lean4android.process.ProcessLauncher
import org.lean4android.process.RunningProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Owns one Lean server process, its framed transport, and open-document version state. */
class LeanLspSession private constructor(
    private val process: RunningProcess,
    private val messages: JsonRpcMessageIO,
) : AutoCloseable, JsonRpcResponder {
    enum class State { STARTED, INITIALIZE_REQUESTED, INITIALIZED, SHUTDOWN_REQUESTED, CLOSED }

    private val versions = DocumentVersionGate()
    private var state = State.STARTED

    companion object {
        fun start(command: ProcessCommand, launcher: ProcessLauncher): LeanLspSession {
            val process = launcher.start(command)
            return LeanLspSession(process, JsonRpcMessageIO(process.standardOutput, process.standardInput))
        }
    }

    @Synchronized
    fun state(): State = state

    fun initialize(requestId: Long, rootUri: String) {
        writeWhen(State.STARTED) { LeanLspLifecycle.initialize(requestId, rootUri) }
        synchronized(this) { state = State.INITIALIZE_REQUESTED }
    }

    fun initialized() {
        writeWhen(State.INITIALIZE_REQUESTED) { LeanLspLifecycle.initialized() }
        synchronized(this) { state = State.INITIALIZED }
    }

    fun didOpen(uri: String, version: Int, text: String) {
        synchronized(this) { require(state == State.INITIALIZED) { "Lean LSP session is not initialized" } }
        versions.opened(uri, version)
        messages.write(LeanLspDocuments.didOpen(uri, version, text))
    }

    fun didChange(uri: String, version: Int, text: String) {
        synchronized(this) { require(state == State.INITIALIZED) { "Lean LSP session is not initialized" } }
        versions.changed(uri, version)
        messages.write(LeanLspDocuments.didChange(uri, version, text))
    }

    fun didClose(uri: String) {
        synchronized(this) { require(state == State.INITIALIZED) { "Lean LSP session is not initialized" } }
        versions.closed(uri)
        messages.write(LeanLspDocuments.didClose(uri))
    }

    fun <T> acceptsDiagnostics(batch: DiagnosticBatch<T>): Boolean = versions.accepts(batch)

    /** Blocking read intended for exactly one supervisor-owned reader coroutine/thread. */
    fun readMessage(): String? = messages.read()

    fun dispatcher(sink: LeanLspEventSink): LeanLspDispatcher =
        LeanLspDispatcher(this, ::acceptsDiagnostics, sink)

    /** Reads and dispatches one message; false means the server transport reached clean EOF. */
    fun readAndDispatch(dispatcher: LeanLspDispatcher): Boolean {
        val payload = readMessage() ?: return false
        dispatcher.dispatch(payload)
        return true
    }

    override fun reply(id: JsonRpcId, result: JsonValue) {
        synchronized(this) { require(state != State.CLOSED) { "Lean LSP session is closed" } }
        messages.write("""{"jsonrpc":"2.0","id":${id.render()},"result":${result.render()}}""")
    }

    fun requestShutdown(requestId: Long) {
        writeWhen(State.INITIALIZED) { LeanLspLifecycle.shutdown(requestId) }
        synchronized(this) { state = State.SHUTDOWN_REQUESTED }
    }

    fun exit(gracePeriod: Duration = 2.seconds): Int? {
        synchronized(this) { require(state == State.SHUTDOWN_REQUESTED) { "Shutdown was not requested" } }
        messages.write(LeanLspLifecycle.exit())
        process.standardInput.close()
        val exitCode = process.awaitExit(gracePeriod) ?: process.terminate(gracePeriod)
        synchronized(this) { state = State.CLOSED }
        process.close()
        return exitCode
    }

    override fun close() {
        val shouldTerminate = synchronized(this) {
            if (state == State.CLOSED) false else {
                state = State.CLOSED
                true
            }
        }
        if (shouldTerminate) process.terminate()
        process.close()
    }

    private inline fun writeWhen(required: State, payload: () -> String) {
        synchronized(this) { require(state == required) { "Expected Lean LSP state $required, found $state" } }
        messages.write(payload())
    }
}
