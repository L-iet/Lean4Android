package org.lean4android.lsp

import java.util.concurrent.atomic.AtomicBoolean

/** Owns the single blocking reader required by one [LeanLspSession]. */
class LeanLspSupervisor(
    private val session: LeanLspSession,
    sink: LeanLspEventSink,
    private val onStopped: (StopReason) -> Unit = {},
) : AutoCloseable {
    sealed interface StopReason {
        data object EndOfStream : StopReason
        data object Closed : StopReason
        data class Failed(val cause: Throwable) : StopReason
    }

    private val dispatcher = session.dispatcher(sink)
    private val started = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private var reader: Thread? = null

    fun start() {
        check(started.compareAndSet(false, true)) { "Lean LSP supervisor has already started" }
        reader = Thread(::readLoop, "lean-lsp-reader").apply {
            isDaemon = true
            start()
        }
    }

    fun isRunning(): Boolean = started.get() && !stopped.get()

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        session.close()
        reader?.takeUnless { it === Thread.currentThread() }?.join(READER_JOIN_MILLIS)
    }

    private fun readLoop() {
        val reason = try {
            while (!closing.get() && session.readAndDispatch(dispatcher)) Unit
            if (closing.get()) StopReason.Closed else StopReason.EndOfStream
        } catch (failure: Throwable) {
            if (closing.get()) StopReason.Closed else StopReason.Failed(failure)
        }
        if (stopped.compareAndSet(false, true)) onStopped(reason)
    }

    private companion object {
        const val READER_JOIN_MILLIS = 2_000L
    }
}
