package org.lean4android.process

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** One-shot runner for the feasibility probe. Long-lived LSP processes use a separate supervisor. */
class JvmCommandRunner : CommandRunner {
    override suspend fun run(command: ProcessCommand): ProcessResult = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(listOf(command.executable.path) + command.arguments)
            .directory(command.workingDirectory)
            .redirectErrorStream(false)
            .apply {
                environment().clear()
                environment().putAll(command.environment)
            }
            .start()

        coroutineScope {
            val stdout = async { process.inputStream.bufferedReader().use { it.readText() } }
            val stderr = async { process.errorStream.bufferedReader().use { it.readText() } }
            val completed = process.waitFor(command.timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            val timedOut = !completed
            if (timedOut) {
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
                process.waitFor()
            }
            ProcessResult(
                exitCode = if (completed) process.exitValue() else -1,
                stdout = stdout.await(),
                stderr = stderr.await(),
                timedOut = timedOut,
            )
        }
    }
}
