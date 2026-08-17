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
import org.lean4android.process.StdinPlan
import org.lean4android.process.InputOperationResult
import org.lean4android.process.CapturedOutput
import java.util.concurrent.CopyOnWriteArraySet

enum class ProjectRunPhase { Build, Program }

sealed interface ProjectRunInput {
    data object ImmediateEof : ProjectRunInput
    data object Interactive : ProjectRunInput
    data class ProjectFile(
        val relativePath: String,
        val expectedBytes: Long,
        val sha256: String,
        val usesSavedVersion: Boolean,
    ) : ProjectRunInput
}

data class ProjectRunSnapshot(
    val id: Long,
    val phase: ProjectRunPhase,
    val activeJob: ProcessJobSnapshot,
    val state: ProcessJobState,
    val elapsedMillis: Long,
    val input: ProjectRunInput,
)

private data class ProjectRunSequence(
    val id: Long,
    var phase: ProjectRunPhase,
    var activeJobId: Long,
    val runCommand: ProcessCommand,
    val entry: String,
    val stdinPlan: StdinPlan,
    val input: ProjectRunInput,
    val startedMillis: Long,
    var buildResult: org.lean4android.process.ProcessResult? = null,
    var terminalState: ProcessJobState? = null,
)

/** Reconnectable owner for user-triggered Lean/Lake jobs. */
class ProjectJobService : Service() {
    fun interface Listener { fun onJobChanged(snapshot: ProcessJobSnapshot) }
    fun interface RunListener { fun onRunChanged(snapshot: ProjectRunSnapshot) }
    inner class LocalBinder : Binder() { fun service(): ProjectJobService = this@ProjectJobService }

    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val runListeners = CopyOnWriteArraySet<RunListener>()
    private val jobs = linkedMapOf<Long, ProcessJobSupervisor>()
    private val runs = linkedMapOf<Long, ProjectRunSequence>()
    private var nextId = 1L
    private var nextRunId = 1L

    override fun onBind(intent: Intent?): IBinder = binder

    fun addListener(listener: Listener) {
        listeners += listener
        snapshots().forEach(listener::onJobChanged)
    }
    fun removeListener(listener: Listener) { listeners -= listener }
    fun addRunListener(listener: RunListener) {
        runListeners += listener
        runSnapshots().forEach(listener::onRunChanged)
    }
    fun removeRunListener(listener: RunListener) { runListeners -= listener }

    @Synchronized fun snapshots(): List<ProcessJobSnapshot> = jobs.map { (id, job) -> snapshot(id, job) }
    @Synchronized fun runSnapshots(): List<ProjectRunSnapshot> = runs.values.map(::runSnapshot)

    fun start(command: ProcessCommand, stdinPlan: StdinPlan = StdinPlan.ImmediateEof): ProcessJobSnapshot {
        val id = synchronized(this) { nextId++ }
        var owned: ProcessJobSupervisor? = null
        val supervisor = ProcessJobSupervisor(
            command,
            JvmProcessLauncher(),
            stdinPlan = stdinPlan,
            onInputChanged = { owned?.let { notify(snapshot(id, it)) } },
        ) { owned?.let { notify(snapshot(id, it)) } }
        owned = supervisor
        synchronized(this) { jobs[id] = supervisor }
        return snapshot(id, supervisor).also(::notify)
    }

    fun startRun(
        buildCommand: ProcessCommand,
        runCommand: ProcessCommand,
        entry: String,
        stdinPlan: StdinPlan = StdinPlan.ImmediateEof,
        input: ProjectRunInput? = null,
    ): ProjectRunSnapshot {
        startService(Intent(this, ProjectJobService::class.java))
        val runId = synchronized(this) { nextRunId++ }
        val runInput = try {
            input ?: defaultRunInput(stdinPlan)
        } catch (failure: Throwable) {
            closeRunStdinPlan(stdinPlan)
            stopSelf()
            throw failure
        }
        val build = try {
            start(buildCommand, StdinPlan.ImmediateEof)
        } catch (failure: Throwable) {
            closeRunStdinPlan(stdinPlan)
            stopSelf()
            throw failure
        }
        val sequence = ProjectRunSequence(
            id = runId,
            phase = ProjectRunPhase.Build,
            activeJobId = build.id,
            runCommand = runCommand,
            entry = entry,
            stdinPlan = stdinPlan,
            input = runInput,
            startedMillis = android.os.SystemClock.elapsedRealtime(),
        )
        synchronized(this) { runs[runId] = sequence }
        advance(sequence, build)
        return synchronized(this) { runSnapshot(sequence) }.also(::notifyRun)
    }

    fun cancelRun(id: Long): Boolean {
        val jobId = synchronized(this) { runs[id]?.takeIf { it.terminalState == null }?.activeJobId } ?: return false
        return cancel(jobId)
    }

    fun sendToRun(id: Long, text: String, appendLf: Boolean): InputOperationResult {
        val jobId = synchronized(this) {
            runs[id]?.takeIf { it.phase == ProjectRunPhase.Program && it.terminalState == null }?.activeJobId
        } ?: return InputOperationResult.Rejected("Run input is not open")
        return send(jobId, text, appendLf)
    }

    fun closeRunInput(id: Long): InputOperationResult {
        val jobId = synchronized(this) {
            runs[id]?.takeIf { it.phase == ProjectRunPhase.Program && it.terminalState == null }?.activeJobId
        } ?: return InputOperationResult.Rejected("Run input is not open")
        return closeInput(jobId)
    }

    fun cancel(id: Long): Boolean = synchronized(this) { jobs[id] }?.cancel() ?: false

    fun send(id: Long, text: String, appendLf: Boolean): InputOperationResult {
        val supervisor = synchronized(this) { jobs[id] }
            ?: return InputOperationResult.Rejected("Unknown job")
        return supervisor.send(text, appendLf).also { notify(snapshot(id, supervisor)) }
    }

    fun closeInput(id: Long): InputOperationResult {
        val supervisor = synchronized(this) { jobs[id] }
            ?: return InputOperationResult.Rejected("Unknown job")
        return supervisor.closeInput().also { notify(snapshot(id, supervisor)) }
    }

    override fun onDestroy() {
        synchronized(this) { jobs.values.toList() }.forEach(ProcessJobSupervisor::close)
        synchronized(this) { runs.values.map(ProjectRunSequence::stdinPlan) }.forEach(::closeRunStdinPlan)
        synchronized(this) { jobs.clear(); runs.clear() }
        listeners.clear()
        runListeners.clear()
        super.onDestroy()
    }

    private fun notify(snapshot: ProcessJobSnapshot) {
        listeners.forEach { it.onJobChanged(snapshot) }
        val affected = synchronized(this) { runs.values.filter { it.activeJobId == snapshot.id && it.terminalState == null } }
        affected.forEach { advance(it, snapshot) }
    }

    @Synchronized private fun advance(sequence: ProjectRunSequence, job: ProcessJobSnapshot) {
        if (sequence.activeJobId != job.id || sequence.terminalState != null) return
        when (val state = job.state) {
            ProcessJobState.Running -> Unit
            is ProcessJobState.Completed -> if (sequence.phase == ProjectRunPhase.Build && state.result.exitCode == 0) {
                sequence.buildResult = state.result
                val program = try {
                    start(sequence.runCommand, sequence.stdinPlan)
                } catch (failure: Throwable) {
                    sequence.terminalState = ProcessJobState.Failed(failure.message ?: failure::class.java.simpleName)
                    closeRunStdinPlan(sequence.stdinPlan)
                    notifyRun(runSnapshot(sequence))
                    if (runs.values.none { it.terminalState == null }) stopSelf()
                    return
                }
                synchronized(this) {
                    sequence.phase = ProjectRunPhase.Program
                    sequence.activeJobId = program.id
                }
                if (program.state != ProcessJobState.Running) advance(sequence, program)
            } else if (sequence.phase == ProjectRunPhase.Program) {
                sequence.terminalState = ProcessJobState.Completed(combineProjectRunResult(
                    requireNotNull(sequence.buildResult), state.result, sequence.entry,
                ))
            } else sequence.terminalState = state
            is ProcessJobState.Cancelled -> sequence.terminalState = state
            is ProcessJobState.Failed -> sequence.terminalState = state
        }
        if (sequence.terminalState != null) closeRunStdinPlan(sequence.stdinPlan)
        notifyRun(synchronized(this) { runSnapshot(sequence) })
        if (sequence.terminalState != null && synchronized(this) { runs.values.none { it.terminalState == null } }) stopSelf()
    }

    private fun runSnapshot(sequence: ProjectRunSequence): ProjectRunSnapshot {
        val job = requireNotNull(jobs[sequence.activeJobId])
        return ProjectRunSnapshot(
            id = sequence.id,
            phase = sequence.phase,
            activeJob = snapshot(sequence.activeJobId, job),
            state = sequence.terminalState ?: ProcessJobState.Running,
            elapsedMillis = android.os.SystemClock.elapsedRealtime() - sequence.startedMillis,
            input = sequence.input,
        )
    }

    private fun notifyRun(snapshot: ProjectRunSnapshot) = runListeners.forEach { it.onRunChanged(snapshot) }

    private fun snapshot(id: Long, supervisor: ProcessJobSupervisor) =
        ProcessJobSnapshot(id, supervisor.state, supervisor.stdinState)
}

internal fun defaultRunInput(plan: StdinPlan): ProjectRunInput = when (plan) {
    StdinPlan.ImmediateEof -> ProjectRunInput.ImmediateEof
    StdinPlan.Interactive -> ProjectRunInput.Interactive
    is StdinPlan.Bytes -> error("Project-file stdin requires selected revision metadata")
}

internal fun closeRunStdinPlan(plan: StdinPlan) {
    if (plan is StdinPlan.Bytes) runCatching { plan.source.close() }
}

internal fun combineProjectRunResult(
    build: org.lean4android.process.ProcessResult,
    program: org.lean4android.process.ProcessResult,
    entry: String,
): org.lean4android.process.ProcessResult {
    val marker = CapturedOutput.fromUtf8("\nBuild completed; running $entry\n")
    val stdout = CapturedOutput.concatenate(build.stdoutCapture, marker, program.stdoutCapture)
    val stderr = CapturedOutput.concatenate(build.stderrCapture, program.stderrCapture)
    return program.copy(
        stdout = stdout.text(),
        stderr = stderr.text(),
        stdoutCapture = stdout,
        stderrCapture = stderr,
    )
}
