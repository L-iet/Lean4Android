package org.lean4android.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.time.Duration

class ProcessJobSupervisorTest {
    @Test fun retainsBoundedStructuredCompletion() {
        val process = FakeRunningProcess("123456789", "warning", exit = 2)
        val job = ProcessJobSupervisor(command(), ProcessLauncher { process }, outputLimitBytes = 5)
        awaitStopped(job)
        val result = (job.state as ProcessJobState.Completed).result
        assertEquals(2, result.exitCode)
        assertEquals("12345", result.stdout)
        assertEquals("warni", result.stderr)
    }

    @Test fun cancellationTerminatesOwnedProcess() {
        val process = FakeRunningProcess("", "", exit = 143, waitUntilTerminated = true)
        val job = ProcessJobSupervisor(command(), ProcessLauncher { process })
        assertTrue(job.cancel())
        awaitStopped(job)
        assertTrue(process.terminated)
        assertTrue(job.state is ProcessJobState.Cancelled)
    }

    private fun awaitStopped(job: ProcessJobSupervisor) {
        repeat(100) {
            if (job.state != ProcessJobState.Running) return
            Thread.sleep(10)
        }
        error("Job did not stop")
    }

    private fun command() = ProcessCommand(File("/bin/true"), workingDirectory = File("/tmp"))

    private class FakeRunningProcess(
        stdout: String,
        stderr: String,
        private val exit: Int,
        private val waitUntilTerminated: Boolean = false,
    ) : RunningProcess {
        override val standardInput = ByteArrayOutputStream()
        override val standardOutput = ByteArrayInputStream(stdout.toByteArray())
        override val standardError = ByteArrayInputStream(stderr.toByteArray())
        @Volatile var terminated = false
        override val isAlive get() = !terminated
        override fun awaitExit(timeout: Duration): Int? {
            while (waitUntilTerminated && !terminated) Thread.sleep(5)
            return exit
        }
        override fun terminate(gracePeriod: Duration): Int { terminated = true; return exit }
        override fun close() { terminated = true }
    }
}
