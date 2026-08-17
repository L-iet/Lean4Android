package org.lean4android.process

import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.days

sealed interface ProcessJobState {
    data object Running : ProcessJobState
    data class Completed(val result: ProcessResult) : ProcessJobState
    data class Failed(val message: String) : ProcessJobState
    data class Cancelled(val stdout: String, val stderr: String) : ProcessJobState
}

sealed interface StdinPlan {
    data object ImmediateEof : StdinPlan
    data object Interactive : StdinPlan
}

sealed interface StdinState {
    data class Open(val acceptedBytes: Long, val pendingBytes: Int) : StdinState
    data class Closed(val reason: CloseReason) : StdinState

    enum class CloseReason { ImmediateEof, UserEof, Cancelled, ProcessExited, Failed }
}

data class ProcessJobSnapshot(
    val id: Long,
    val state: ProcessJobState,
    val stdin: StdinState = StdinState.Closed(StdinState.CloseReason.ImmediateEof),
)

sealed interface InputOperationResult {
    data class Accepted(val bytes: Int) : InputOperationResult
    data object AlreadyClosed : InputOperationResult
    data class Rejected(val reason: String) : InputOperationResult
}

/** Retainable one-shot job owner with bounded output and explicit cancellation. */
class ProcessJobSupervisor(
    command: ProcessCommand,
    launcher: ProcessLauncher,
    private val outputLimitBytes: Int = 1024 * 1024,
    stdinPlan: StdinPlan = StdinPlan.ImmediateEof,
    private val pendingInputLimitBytes: Int = 64 * 1024,
    private val onChanged: (ProcessJobState) -> Unit = {},
) : AutoCloseable {
    private val process = launcher.start(command)
    private val cancelled = AtomicBoolean(false)
    private val stdout = BoundedOutput(outputLimitBytes)
    private val stderr = BoundedOutput(outputLimitBytes)
    private val stdoutThread = drain("lean-job-stdout", process.standardOutput, stdout)
    private val stderrThread = drain("lean-job-stderr", process.standardError, stderr)
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val inputLock = Object()
    private val inputQueue = ArrayDeque<ByteArray>()
    private var acceptedInputBytes = 0L
    private var pendingInputBytes = 0
    private var inputCloseRequested = stdinPlan == StdinPlan.ImmediateEof
    @Volatile var stdinState: StdinState = if (stdinPlan == StdinPlan.ImmediateEof) {
        StdinState.Closed(StdinState.CloseReason.ImmediateEof)
    } else StdinState.Open(0, 0)
        private set
    private val inputThread = thread(name = "lean-job-stdin", isDaemon = true) { writeInput(stdinPlan) }
    @Volatile var state: ProcessJobState = ProcessJobState.Running
        private set
    private val waiter = thread(name = "lean-job-waiter", isDaemon = true) {
        runCatching {
            val exit = process.awaitExit(365.days) ?: process.terminate()
            synchronized(inputLock) {
                if (!inputCloseRequested) stdinState = StdinState.Closed(StdinState.CloseReason.ProcessExited)
                inputCloseRequested = true
                inputLock.notifyAll()
            }
            runCatching { process.standardInput.close() }
            inputThread.join()
            stdoutThread.join()
            stderrThread.join()
            if (cancelled.get()) ProcessJobState.Cancelled(stdout.text(), stderr.text())
            else ProcessJobState.Completed(ProcessResult(exit, stdout.text(), stderr.text(), false))
        }.getOrElse { ProcessJobState.Failed(it.message ?: it::class.java.simpleName) }
            .also(::publish)
    }

    fun cancel(): Boolean {
        if (state != ProcessJobState.Running || !cancelled.compareAndSet(false, true)) return false
        closeInput(StdinState.CloseReason.Cancelled)
        process.terminate()
        return true
    }

    fun send(text: String, appendLf: Boolean = false): InputOperationResult {
        val content = if (appendLf) text.toByteArray(Charsets.UTF_8) + byteArrayOf('\n'.code.toByte())
        else text.toByteArray(Charsets.UTF_8)
        synchronized(inputLock) {
            if (stdinState !is StdinState.Open || inputCloseRequested) return InputOperationResult.AlreadyClosed
            if (content.size > pendingInputLimitBytes - pendingInputBytes) {
                return InputOperationResult.Rejected("Input is still being delivered")
            }
            inputQueue.addLast(content)
            acceptedInputBytes += content.size
            pendingInputBytes += content.size
            stdinState = StdinState.Open(acceptedInputBytes, pendingInputBytes)
            inputLock.notifyAll()
            return InputOperationResult.Accepted(content.size)
        }
    }

    fun closeInput(reason: StdinState.CloseReason = StdinState.CloseReason.UserEof): InputOperationResult {
        synchronized(inputLock) {
            if (inputCloseRequested) return InputOperationResult.AlreadyClosed
            inputCloseRequested = true
            stdinState = StdinState.Closed(reason)
            inputLock.notifyAll()
            return InputOperationResult.Accepted(0)
        }
    }

    override fun close() {
        cancel()
        waiter.join(3_000)
        process.close()
    }

    private fun writeInput(plan: StdinPlan) {
        if (plan == StdinPlan.ImmediateEof) {
            runCatching { process.standardInput.close() }
            return
        }
        try {
            while (true) {
                val next = synchronized(inputLock) {
                    while (inputQueue.isEmpty() && !inputCloseRequested) inputLock.wait()
                    if (inputQueue.isEmpty()) null else inputQueue.removeFirst().also {
                        pendingInputBytes -= it.size
                        if (!inputCloseRequested) stdinState = StdinState.Open(acceptedInputBytes, pendingInputBytes)
                    }
                } ?: break
                process.standardInput.write(next)
                process.standardInput.flush()
            }
            process.standardInput.close()
        } catch (_: Exception) {
            synchronized(inputLock) {
                inputCloseRequested = true
                stdinState = StdinState.Closed(StdinState.CloseReason.Failed)
            }
        }
    }

    private fun publish(value: ProcessJobState) {
        state = value
        onChanged(value)
    }

    private fun drain(name: String, input: InputStream, output: BoundedOutput) = thread(name = name, isDaemon = true) {
        input.use {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                output.append(buffer, count)
            }
        }
    }
}

private class BoundedOutput(private val limit: Int) {
    private val bytes = java.io.ByteArrayOutputStream()
    @Synchronized fun append(buffer: ByteArray, count: Int) {
        val remaining = limit - bytes.size()
        if (remaining > 0) bytes.write(buffer, 0, minOf(count, remaining))
    }
    @Synchronized fun text(): String = bytes.toString(Charsets.UTF_8.name())
}
