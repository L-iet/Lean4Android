package org.lean4android.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lean4android.process.ProcessCommand
import org.lean4android.process.ProcessLauncher
import org.lean4android.process.RunningProcess
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Duration

class LeanLspSessionTest {
    @Test
    fun `session owns lifecycle document versions and graceful exit`() {
        val process = FakeRunningProcess(exitCode = 0)
        val session = LeanLspSession.start(command(), ProcessLauncher { process })

        session.initialize(1, "file:///project")
        session.initialized()
        session.didOpen("file:///project/Main.lean", 1, "example : False := by rfl")
        session.didChange("file:///project/Main.lean", 2, "example : True := by trivial")
        assertFalse(session.acceptsDiagnostics(DiagnosticBatch("file:///project/Main.lean", 1, emptyList<Any>())))
        assertTrue(session.acceptsDiagnostics(DiagnosticBatch("file:///project/Main.lean", 2, emptyList<Any>())))
        session.requestShutdown(2)

        assertEquals(0, session.exit())
        assertEquals(LeanLspSession.State.CLOSED, session.state())
        assertTrue(process.inputClosed)
        assertFalse(process.terminated)
        val payloads = framedPayloads(process.written.toByteArray())
        assertEquals(listOf("initialize", "initialized", "didOpen", "didChange", "shutdown", "exit"), payloads.map(::kind))
    }

    @Test
    fun `close without shutdown forcibly owns process cleanup`() {
        val process = FakeRunningProcess(exitCode = null)
        val session = LeanLspSession.start(command(), ProcessLauncher { process })

        session.close()

        assertTrue(process.terminated)
        assertTrue(process.closed)
        assertEquals(LeanLspSession.State.CLOSED, session.state())
    }

    @Test
    fun `invalid lifecycle ordering is rejected`() {
        val session = LeanLspSession.start(command(), ProcessLauncher { FakeRunningProcess(0) })

        val failure = runCatching { session.didOpen("file:///Main.lean", 1, "#check Nat") }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        session.close()
    }

    private fun command() = ProcessCommand(File("/tool/lake"), listOf("serve"), File("/project"))

    private fun framedPayloads(bytes: ByteArray): List<String> {
        val input = ByteArrayInputStream(bytes)
        val io = JsonRpcMessageIO(input, ByteArrayOutputStream())
        return generateSequence { io.read() }.toList()
    }

    private fun kind(payload: String): String = when {
        "\"method\":\"initialize\"" in payload -> "initialize"
        "\"method\":\"initialized\"" in payload -> "initialized"
        "didOpen" in payload -> "didOpen"
        "didChange" in payload -> "didChange"
        "\"method\":\"shutdown\"" in payload -> "shutdown"
        "\"method\":\"exit\"" in payload -> "exit"
        else -> error("Unknown payload: $payload")
    }

    private class FakeRunningProcess(private val exitCode: Int?) : RunningProcess {
        val written = ByteArrayOutputStream()
        var inputClosed = false
        var terminated = false
        var closed = false

        override val standardInput: OutputStream = object : OutputStream() {
            override fun write(value: Int) = written.write(value)
            override fun write(bytes: ByteArray, offset: Int, length: Int) = written.write(bytes, offset, length)
            override fun close() { inputClosed = true }
        }
        override val standardOutput: InputStream = ByteArrayInputStream(byteArrayOf())
        override val standardError: InputStream = ByteArrayInputStream(byteArrayOf())
        override val isAlive: Boolean get() = !closed
        override fun awaitExit(timeout: Duration): Int? = exitCode
        override fun terminate(gracePeriod: Duration): Int {
            terminated = true
            return exitCode ?: -1
        }
        override fun close() { closed = true }
    }
}
