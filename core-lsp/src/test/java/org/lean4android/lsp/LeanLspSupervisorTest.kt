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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

class LeanLspSupervisorTest {
    @Test
    fun `reader dispatches messages replies to registration and reports EOF`() {
        val inbound = framed(
            """{"jsonrpc":"2.0","id":7,"method":"client/registerCapability","params":{"registrations":[]}}""",
            """{"jsonrpc":"2.0","method":"window/logMessage","params":{"type":3,"message":"ready"}}""",
        )
        val process = FakeRunningProcess(inbound, "server warning\n".toByteArray())
        val session = LeanLspSession.start(command(), ProcessLauncher { process })
        val notifications = mutableListOf<String>()
        var reason: LeanLspSupervisor.StopReason? = null
        val stopped = CountDownLatch(1)
        val supervisor = LeanLspSupervisor(session, object : LeanLspEventSink {
            override fun onNotification(notification: JsonRpcEnvelope.Notification) {
                notifications += notification.method
            }
        }) {
            reason = it
            stopped.countDown()
        }

        supervisor.start()

        assertTrue(stopped.await(2, TimeUnit.SECONDS))
        assertEquals(LeanLspSupervisor.StopReason.EndOfStream, reason)
        assertEquals(listOf("window/logMessage"), notifications)
        assertEquals(
            listOf("""{"jsonrpc":"2.0","id":7,"result":null}"""),
            framedPayloads(process.written.toByteArray()),
        )
        assertFalse(supervisor.isRunning())
        repeat(20) {
            if ("server warning" in supervisor.stderrTail()) return@repeat
            Thread.sleep(5)
        }
        assertTrue(supervisor.stderrTail().contains("server warning"))
        supervisor.close()
        assertTrue(process.terminated)
    }

    @Test
    fun `malformed server payload is surfaced as reader failure`() {
        val process = FakeRunningProcess(framed("""{"jsonrpc":"1.0"}"""))
        val session = LeanLspSession.start(command(), ProcessLauncher { process })
        var reason: LeanLspSupervisor.StopReason? = null
        val stopped = CountDownLatch(1)
        val supervisor = LeanLspSupervisor(session, object : LeanLspEventSink {}) {
            reason = it
            stopped.countDown()
        }

        supervisor.start()

        assertTrue(stopped.await(2, TimeUnit.SECONDS))
        assertTrue(reason is LeanLspSupervisor.StopReason.Failed)
        supervisor.close()
    }

    @Test
    fun `supervisor can only start once`() {
        val supervisor = LeanLspSupervisor(
            LeanLspSession.start(command(), ProcessLauncher { FakeRunningProcess(byteArrayOf()) }),
            object : LeanLspEventSink {},
        )
        supervisor.start()

        val failure = runCatching { supervisor.start() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        supervisor.close()
    }

    private fun command() = ProcessCommand(File("/tool/lake"), listOf("serve"), File("/project"))

    private fun framed(vararg payloads: String): ByteArray = ByteArrayOutputStream().also { output ->
        val io = JsonRpcMessageIO(ByteArrayInputStream(byteArrayOf()), output)
        payloads.forEach(io::write)
    }.toByteArray()

    private fun framedPayloads(bytes: ByteArray): List<String> {
        val io = JsonRpcMessageIO(ByteArrayInputStream(bytes), ByteArrayOutputStream())
        return generateSequence(io::read).toList()
    }

    private class FakeRunningProcess(inbound: ByteArray, errors: ByteArray = byteArrayOf()) : RunningProcess {
        val written = ByteArrayOutputStream()
        var terminated = false
        override val standardInput: OutputStream = written
        override val standardOutput: InputStream = ByteArrayInputStream(inbound)
        override val standardError: InputStream = ByteArrayInputStream(errors)
        override val isAlive: Boolean get() = !terminated
        override fun awaitExit(timeout: Duration): Int? = null
        override fun terminate(gracePeriod: Duration): Int { terminated = true; return -1 }
        override fun close() = Unit
    }
}
