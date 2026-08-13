package org.lean4android.process

import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** Starts an interactive child without a shell. Callers own all three process streams. */
class JvmProcessLauncher : ProcessLauncher {
    override fun start(command: ProcessCommand): RunningProcess {
        val process = ProcessBuilder(listOf(command.executable.path) + command.arguments)
            .directory(command.workingDirectory)
            .redirectErrorStream(false)
            .apply {
                environment().clear()
                environment().putAll(command.environment)
            }
            .start()
        return JvmRunningProcess(process)
    }
}

private class JvmRunningProcess(private val process: Process) : RunningProcess {
    override val standardInput get() = process.outputStream
    override val standardOutput get() = process.inputStream
    override val standardError get() = process.errorStream
    override val isAlive get() = process.isAlive

    override fun awaitExit(timeout: Duration): Int? =
        if (process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) process.exitValue() else null

    override fun terminate(gracePeriod: Duration): Int {
        standardInput.close()
        process.destroy()
        if (!process.waitFor(gracePeriod.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor()
        }
        return process.exitValue()
    }

    override fun close() {
        if (process.isAlive) terminate()
        standardOutput.close()
        standardError.close()
    }
}
