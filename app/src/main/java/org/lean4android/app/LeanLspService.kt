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

    data class SessionSnapshot(val projectId: String, val generation: Long, val state: SessionState)

    fun interface Listener {
        fun onSessionChanged(snapshot: SessionSnapshot)
    }

    inner class LocalBinder : Binder() {
        fun service(): LeanLspService = this@LeanLspService
    }

    private data class OwnedSession(
        val generation: Long,
        val supervisor: LeanLspSupervisor,
        var state: SessionState,
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
        SessionSnapshot(projectId, owned.generation, owned.state)
    }

    fun startSession(
        projectId: String,
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
            sessions[projectId] = OwnedSession(generation, supervisor, SessionState.Running)
            generation to supervisor
        }
        val snapshot = SessionSnapshot(projectId, generation, SessionState.Running)
        notifyListeners(snapshot)
        supervisor.start()
        return snapshot
    }

    fun stopSession(projectId: String): Boolean {
        val owned = synchronized(this) { sessions.remove(projectId) } ?: return false
        owned.supervisor.close()
        notifyListeners(SessionSnapshot(projectId, owned.generation, SessionState.Stopped(LeanLspSupervisor.StopReason.Closed)))
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
            SessionSnapshot(projectId, generation, owned.state)
        }
        notifyListeners(snapshot)
    }

    private fun notifyListeners(snapshot: SessionSnapshot) {
        listeners.forEach { it.onSessionChanged(snapshot) }
    }
}
