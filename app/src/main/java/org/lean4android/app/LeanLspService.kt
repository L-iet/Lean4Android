package org.lean4android.app

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import org.lean4android.lsp.LeanLspEventSink
import org.lean4android.lsp.LeanLspSession
import org.lean4android.lsp.LeanLspSupervisor
import org.lean4android.process.ProcessCommand
import org.lean4android.process.ProcessLauncher
import java.util.concurrent.CopyOnWriteArraySet

/** Local process owner. Activities reconnect through [LocalBinder] and observe snapshots. */
class LeanLspService : Service() {
    sealed interface SessionState {
        data object Running : SessionState
        data class Stopped(val reason: LeanLspSupervisor.StopReason) : SessionState
    }

    data class SessionSnapshot(
        val projectId: String,
        val generation: Long,
        val state: SessionState,
        val initialized: Boolean,
    )

    fun interface Listener {
        fun onSessionChanged(snapshot: SessionSnapshot)
    }

    inner class LocalBinder : Binder() {
        fun service(): LeanLspService = this@LeanLspService
    }

    private data class OwnedSession(
        val generation: Long,
        val session: LeanLspSession,
        val supervisor: LeanLspSupervisor,
        var state: SessionState,
        var initialized: Boolean = false,
        val documents: EditorLspCoordinator = EditorLspCoordinator(),
    )

    private val binder = LocalBinder()
    private val sessions = linkedMapOf<String, OwnedSession>()
    private val listeners = CopyOnWriteArraySet<Listener>()
    private var nextGeneration = 1L

    override fun onBind(intent: Intent?): IBinder = binder

    fun addListener(listener: Listener) {
        listeners += listener
        snapshots().forEach(listener::onSessionChanged)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    @Synchronized
    fun snapshots(): List<SessionSnapshot> = sessions.map { (projectId, owned) ->
        SessionSnapshot(projectId, owned.generation, owned.state, owned.initialized)
    }

    fun startSession(
        projectId: String,
        rootUri: String,
        command: ProcessCommand,
        launcher: ProcessLauncher,
        sink: LeanLspEventSink,
    ): SessionSnapshot {
        require(projectId.isNotBlank()) { "Project ID cannot be blank" }
        val (generation, supervisor) = synchronized(this) {
            check(projectId !in sessions) { "Project already owns an LSP session: $projectId" }
            val generation = nextGeneration++
            val session = LeanLspSession.start(command, launcher)
            val supervisor = LeanLspSupervisor(session, sink) { reason ->
                recordStopped(projectId, generation, reason)
            }
            sessions[projectId] = OwnedSession(generation, session, supervisor, SessionState.Running)
            generation to supervisor
        }
        val snapshot = SessionSnapshot(projectId, generation, SessionState.Running, initialized = false)
        notifyListeners(snapshot)
        supervisor.start()
        withSession(projectId, generation) { it.initialize(INITIALIZE_REQUEST_ID, rootUri) }
        return snapshot
    }

    fun initialized(projectId: String, generation: Long) {
        val snapshot = synchronized(this) {
            val owned = requireOwned(projectId, generation)
            if (!owned.initialized) {
                owned.session.initialized()
                owned.initialized = true
            }
            SessionSnapshot(projectId, generation, owned.state, owned.initialized)
        }
        notifyListeners(snapshot)
    }

    fun didOpen(projectId: String, generation: Long, uri: String, version: Int, text: String) =
        withInitializedSession(projectId, generation) { it.didOpen(uri, version, text) }

    fun didChange(projectId: String, generation: Long, uri: String, version: Int, text: String) =
        withInitializedSession(projectId, generation) { it.didChange(uri, version, text) }

    fun didSave(projectId: String, generation: Long, uri: String, text: String? = null) =
        withInitializedSession(projectId, generation) { it.didSave(uri, text) }

    fun didClose(projectId: String, generation: Long, uri: String) =
        withInitializedSession(projectId, generation) { it.didClose(uri) }

    fun request(projectId: String, generation: Long, payload: String) =
        withInitializedSession(projectId, generation) { it.request(payload) }

    fun documentVersion(projectId: String, generation: Long, uri: String): Int? = synchronized(this) {
        requireOwned(projectId, generation).documents.currentVersion(uri)
    }

    /** Reconciles the complete editor buffer set without duplicating opens after Activity reconnection. */
    fun synchronizeDocuments(projectId: String, generation: Long, buffers: Map<String, String>) {
        synchronized(this) {
            val owned = requireOwned(projectId, generation)
            check(owned.initialized) { "Lean LSP session is still initializing" }
            val operations: List<EditorLspCoordinator.Operation> = if (owned.documents.generation != generation) {
                owned.documents.activate(generation, buffers)
            } else buildList<EditorLspCoordinator.Operation> {
                owned.documents.openUris().filter { it !in buffers }.forEach { uri ->
                    owned.documents.close(uri)?.let(::add)
                }
                buffers.forEach { (uri, text) ->
                    if (owned.documents.currentVersion(uri) == null) {
                        // Rebuild the current set through a fresh coordinator generation so new opens remain version 1.
                        // Existing documents are replayed below only when the server generation changes.
                        add(EditorLspCoordinator.Operation.Open(uri, 1, text))
                    } else owned.documents.edit(uri, text)?.let(::add)
                }
            }
            operations.forEach { operation ->
                when (operation) {
                    is EditorLspCoordinator.Operation.Open -> {
                        if (owned.documents.currentVersion(operation.uri) == null) {
                            // Track a newly opened tab without resetting existing document versions.
                            owned.documents.add(operation.uri, operation.text)
                        }
                        owned.session.didOpen(operation.uri, operation.version, operation.text)
                    }
                    is EditorLspCoordinator.Operation.Change -> owned.session.didChange(operation.uri, operation.version, operation.text)
                    is EditorLspCoordinator.Operation.Close -> owned.session.didClose(operation.uri)
                    is EditorLspCoordinator.Operation.Save -> owned.session.didSave(operation.uri, operation.text)
                }
            }
        }
    }

    fun stopSession(projectId: String): Boolean {
        val owned = synchronized(this) { sessions.remove(projectId) } ?: return false
        owned.supervisor.close()
        notifyListeners(SessionSnapshot(projectId, owned.generation, SessionState.Stopped(LeanLspSupervisor.StopReason.Closed), owned.initialized))
        return true
    }

    override fun onDestroy() {
        val owned = synchronized(this) {
            val values = sessions.values.toList()
            sessions.clear()
            values
        }
        owned.forEach { it.supervisor.close() }
        listeners.clear()
        super.onDestroy()
    }

    private fun recordStopped(projectId: String, generation: Long, reason: LeanLspSupervisor.StopReason) {
        val snapshot = synchronized(this) {
            val owned = sessions[projectId]
            if (owned == null || owned.generation != generation) return
            owned.state = SessionState.Stopped(reason)
            SessionSnapshot(projectId, generation, owned.state, owned.initialized)
        }
        notifyListeners(snapshot)
    }

    private fun notifyListeners(snapshot: SessionSnapshot) {
        listeners.forEach { it.onSessionChanged(snapshot) }
    }

    private inline fun <T> withSession(projectId: String, generation: Long, action: (LeanLspSession) -> T): T =
        synchronized(this) { action(requireOwned(projectId, generation).session) }

    private inline fun <T> withInitializedSession(projectId: String, generation: Long, action: (LeanLspSession) -> T): T =
        synchronized(this) {
            val owned = requireOwned(projectId, generation)
            check(owned.initialized) { "Lean LSP session is still initializing" }
            action(owned.session)
        }

    private fun requireOwned(projectId: String, generation: Long): OwnedSession {
        val owned = sessions[projectId] ?: error("Project has no LSP session: $projectId")
        require(owned.generation == generation) {
            "Stale LSP generation for $projectId: current=${owned.generation}, requested=$generation"
        }
        return owned
    }

    companion object {
        const val INITIALIZE_REQUEST_ID = 1L
    }
}
