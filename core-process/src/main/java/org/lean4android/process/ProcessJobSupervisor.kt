package org.lean4android.process

import java.io.InputStream
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.days

sealed interface ProcessJobState {
    data object Running : ProcessJobState
    data class Completed(val result: ProcessResult) : ProcessJobState
    data class Failed(val message: String) : ProcessJobState
    data class Cancelled(val stdout: String, val stderr: String) : ProcessJobState
}

data class ProcessJobSnapshot(val id: Long, val state: ProcessJobState)

/** Retainable one-shot job owner with bounded output and explicit cancellation. */
class ProcessJobSupervisor(
    command: ProcessCommand,
    launcher: ProcessLauncher,
    private val outputLimitBytes: Int = 1024 * 1024,
    private val onChanged: (ProcessJobState) -> Unit = {},
) : AutoCloseable {
    private val process = launcher.start(command)
    private val cancelled = AtomicBoolean(false)
    private val stdout = BoundedOutput(outputLimitBytes)
    private val stderr = BoundedOutput(outputLimitBytes)
    private val stdoutThread = drain("lean-job-stdout", process.standardOutput, stdout)
    private val stderrThread = drain("lean-job-stderr", process.standardError, stderr)
    @Volatile var state: ProcessJobState = ProcessJobState.Running
        private set
    private val waiter = thread(name = "lean-job-waiter", isDaemon = true) {
        runCatching {
            val exit = process.awaitExit(365.days) ?: process.terminate()
            stdoutThread.join()
            stderrThread.join()
            if (cancelled.get()) ProcessJobState.Cancelled(stdout.text(), stderr.text())
            else ProcessJobState.Completed(ProcessResult(exit, stdout.text(), stderr.text(), false))
        }.getOrElse { ProcessJobState.Failed(it.message ?: it::class.java.simpleName) }
            .also(::publish)
    }

    fun cancel(): Boolean {
        if (state != ProcessJobState.Running || !cancelled.compareAndSet(false, true)) return false
        process.terminate()
        return true
    }

    override fun close() {
        cancel()
        waiter.join(3_000)
        process.close()
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
