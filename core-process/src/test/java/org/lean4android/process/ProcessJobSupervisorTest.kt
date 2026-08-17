package org.lean4android.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
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

    @Test fun cancellationContainsInterruptedDrainReads() {
        val output = InterruptingInputStream()
        val process = FakeRunningProcess("", "", exit = 143, waitUntilTerminated = true, stdoutInput = output)
        val job = ProcessJobSupervisor(command(), ProcessLauncher { process })

        assertTrue(job.cancel())
        awaitStopped(job)

        assertTrue(output.closed)
        assertTrue(job.state is ProcessJobState.Cancelled)
    }

    @Test fun immediateEofIsTheDefault() {
        val input = TrackingOutputStream()
        val process = FakeRunningProcess("", "", exit = 0, input = input, waitUntilInputClosed = true)
        val job = ProcessJobSupervisor(command(), ProcessLauncher { process })
        awaitStopped(job)
        assertTrue(input.closed)
        assertEquals(StdinState.Closed(StdinState.CloseReason.ImmediateEof), job.stdinState)
    }

    @Test fun interactiveInputIsOrderedBoundedAndClosedExplicitly() {
        val input = TrackingOutputStream()
        val process = FakeRunningProcess("", "", exit = 0, input = input, waitUntilInputClosed = true)
        val job = ProcessJobSupervisor(
            command(), ProcessLauncher { process }, stdinPlan = StdinPlan.Interactive,
            pendingInputLimitBytes = 8,
        )
        assertEquals(InputOperationResult.Accepted(3), job.send("α", appendLf = true))
        assertEquals(InputOperationResult.Rejected("Input is still being delivered"), job.send("123456789"))
        assertEquals(InputOperationResult.Accepted(0), job.closeInput())
        awaitStopped(job)
        assertEquals("α\n", input.bytes.toString(Charsets.UTF_8.name()))
        assertEquals(StdinState.Closed(StdinState.CloseReason.UserEof), job.stdinState)
        assertEquals(InputOperationResult.AlreadyClosed, job.send("late"))
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
        private val input: OutputStream = ByteArrayOutputStream(),
        private val waitUntilInputClosed: Boolean = false,
        stdoutInput: InputStream? = null,
        stderrInput: InputStream? = null,
    ) : RunningProcess {
        override val standardInput = input
        override val standardOutput = stdoutInput ?: ByteArrayInputStream(stdout.toByteArray())
        override val standardError = stderrInput ?: ByteArrayInputStream(stderr.toByteArray())
        @Volatile var terminated = false
        override val isAlive get() = !terminated
        override fun awaitExit(timeout: Duration): Int? {
            while (waitUntilTerminated && !terminated) Thread.sleep(5)
            while (waitUntilInputClosed && !(input as TrackingOutputStream).closed) Thread.sleep(5)
            return exit
        }
        override fun terminate(gracePeriod: Duration): Int {
            terminated = true
            standardOutput.close()
            standardError.close()
            return exit
        }
        override fun close() { terminated = true }
    }

    private class TrackingOutputStream : OutputStream() {
        val bytes = ByteArrayOutputStream()
        @Volatile var closed = false
        override fun write(value: Int) = bytes.write(value)
        override fun write(buffer: ByteArray, offset: Int, length: Int) = bytes.write(buffer, offset, length)
        override fun close() { closed = true }
    }

    private class InterruptingInputStream : InputStream() {
        @Volatile var closed = false
        override fun read(): Int {
            while (!closed) Thread.sleep(5)
            throw InterruptedIOException("read interrupted")
        }
        override fun close() { closed = true }
    }
}
