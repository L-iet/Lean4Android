package org.lean4android.app

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import org.lean4android.process.JvmProcessLauncher
import org.lean4android.process.ProcessCommand
import org.lean4android.process.ProcessJobSnapshot
import org.lean4android.process.ProcessJobState
import org.lean4android.process.ProcessJobSupervisor
import java.util.concurrent.CopyOnWriteArraySet

/** Reconnectable owner for user-triggered Lean/Lake jobs. */
class ProjectJobService : Service() {
    fun interface Listener { fun onJobChanged(snapshot: ProcessJobSnapshot) }
    inner class LocalBinder : Binder() { fun service(): ProjectJobService = this@ProjectJobService }

    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val jobs = linkedMapOf<Long, ProcessJobSupervisor>()
    private var nextId = 1L

    override fun onBind(intent: Intent?): IBinder = binder

    fun addListener(listener: Listener) {
        listeners += listener
        snapshots().forEach(listener::onJobChanged)
    }
    fun removeListener(listener: Listener) { listeners -= listener }

    @Synchronized fun snapshots(): List<ProcessJobSnapshot> = jobs.map { (id, job) -> ProcessJobSnapshot(id, job.state) }

    fun start(command: ProcessCommand): ProcessJobSnapshot {
        val id = synchronized(this) { nextId++ }
        val supervisor = ProcessJobSupervisor(command, JvmProcessLauncher()) { state -> notify(ProcessJobSnapshot(id, state)) }
        synchronized(this) { jobs[id] = supervisor }
        return ProcessJobSnapshot(id, ProcessJobState.Running).also(::notify)
    }

    fun cancel(id: Long): Boolean = synchronized(this) { jobs[id] }?.cancel() ?: false

    override fun onDestroy() {
        synchronized(this) { jobs.values.toList().also { jobs.clear() } }.forEach(ProcessJobSupervisor::close)
        listeners.clear()
        super.onDestroy()
    }

    private fun notify(snapshot: ProcessJobSnapshot) = listeners.forEach { it.onJobChanged(snapshot) }
}
