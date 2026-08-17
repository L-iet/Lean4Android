package org.lean4android.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.IOException
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

    @Test fun projectBytesStreamInBoundedChunksAndCloseOnExactRevision() {
        val bytes = ByteArray(DEFAULT_BUFFER_SIZE * 2 + 7) { (it % 251).toByte() }
        val input = TrackingOutputStream()
        val states = mutableListOf<StdinState>()
        val process = FakeRunningProcess("", "", exit = 0, input = input, waitUntilInputClosed = true)
        val job = ProcessJobSupervisor(
            command(), ProcessLauncher { process },
            stdinPlan = StdinPlan.Bytes(ByteArraySource(bytes)),
            onInputChanged = { synchronized(states) { states += it } },
        )

        awaitStopped(job)

        assertTrue(input.bytes.toByteArray().contentEquals(bytes))
        assertEquals(StdinState.Closed(StdinState.CloseReason.TransferComplete), job.stdinState)
        assertTrue(states.filterIsInstance<StdinState.Streaming>().size >= 3)
        assertEquals(ProcessJobState.Completed(ProcessResult(0, "", "", false)), job.state)
    }

    @Test fun emptyProjectBytesCloseAsCompletedTransfer() {
        val input = TrackingOutputStream()
        val process = FakeRunningProcess("", "", exit = 0, input = input, waitUntilInputClosed = true)
        val job = ProcessJobSupervisor(
            command(), ProcessLauncher { process },
            stdinPlan = StdinPlan.Bytes(ByteArraySource(byteArrayOf())),
        )

        awaitStopped(job)

        assertEquals(0, input.bytes.size())
        assertEquals(StdinState.Closed(StdinState.CloseReason.TransferComplete), job.stdinState)
    }

    @Test fun shortProjectByteRevisionFailsInputAndCancelsChild() {
        val input = TrackingOutputStream()
        val process = FakeRunningProcess("", "", exit = 143, input = input, waitUntilTerminated = true)
        val source = object : InputByteSource {
            override val expectedBytes = 5L
            override fun openStream() = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        }
        val job = ProcessJobSupervisor(command(), ProcessLauncher { process }, stdinPlan = StdinPlan.Bytes(source))

        awaitStopped(job)

        assertTrue(process.terminated)
        assertTrue(job.state is ProcessJobState.Cancelled)
        val failure = job.stdinState as StdinState.Failed
        assertEquals(3, failure.sentBytes)
        assertEquals(5, failure.totalBytes)
        assertTrue(failure.message.contains("3 of 5"))
    }

    @Test fun projectByteReadFailureCancelsChildWithoutHidingProgress() {
        val input = TrackingOutputStream()
        val process = FakeRunningProcess("", "", exit = 143, input = input, waitUntilTerminated = true)
        val source = object : InputByteSource {
            override val expectedBytes = 4L
            override fun openStream() = object : InputStream() {
                private var emitted = false
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (emitted) throw IOException("fixture read failure")
                    emitted = true
                    buffer[offset] = 7
                    return 1
                }
                override fun read(): Int = error("bulk read expected")
            }
        }
        val job = ProcessJobSupervisor(command(), ProcessLauncher { process }, stdinPlan = StdinPlan.Bytes(source))

        awaitStopped(job)

        assertTrue(process.terminated)
        val failure = job.stdinState as StdinState.Failed
        assertEquals(1, failure.sentBytes)
        assertTrue(failure.message.contains("fixture read failure"))
    }

    @Test fun cancellationClosesAProjectSourceBlockedDuringTransfer() {
        val sourceInput = InterruptingInputStream()
        val process = FakeRunningProcess("", "", exit = 143, waitUntilTerminated = true)
        val source = object : InputByteSource {
            override val expectedBytes = 1L
            override fun openStream() = sourceInput
        }
        val job = ProcessJobSupervisor(command(), ProcessLauncher { process }, stdinPlan = StdinPlan.Bytes(source))

        assertTrue(job.cancel())
        awaitStopped(job)

        assertTrue(sourceInput.closed)
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

    private class ByteArraySource(private val bytes: ByteArray) : InputByteSource {
        override val expectedBytes = bytes.size.toLong()
        override fun openStream() = ByteArrayInputStream(bytes)
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
