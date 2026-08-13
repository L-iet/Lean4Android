package org.lean4android.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class JvmProcessLauncherTest {
    @Test
    fun `interactive process keeps protocol and error streams separate`() {
        val process = JvmProcessLauncher().start(
            ProcessCommand(
                executable = File("/bin/sh"),
                arguments = listOf("-c", "read line; printf 'reply:%s' \"\$line\"; printf 'server-log' >&2"),
                workingDirectory = File("/tmp"),
                environment = mapOf("PATH" to "/usr/bin:/bin"),
            ),
        )

        process.use {
            it.standardInput.write("hello\n".toByteArray())
            it.standardInput.flush()

            assertEquals(0, it.awaitExit(2.seconds))
            assertEquals("reply:hello", it.standardOutput.bufferedReader().readText())
            assertEquals("server-log", it.standardError.bufferedReader().readText())
        }
    }

    @Test
    fun `terminate escalates a child that ignores the graceful signal`() {
        val process = JvmProcessLauncher().start(
            ProcessCommand(
                executable = File("/bin/sh"),
                arguments = listOf("-c", "trap '' TERM; while :; do sleep 1; done"),
                workingDirectory = File("/tmp"),
            ),
        )

        assertNull(process.awaitExit(20.milliseconds))
        process.terminate(20.milliseconds)
        assertFalse(process.isAlive)
    }

    @Test
    fun `launcher clears inherited environment`() {
        val process = JvmProcessLauncher().start(
            ProcessCommand(
                executable = File("/usr/bin/env"),
                workingDirectory = File("/tmp"),
                environment = mapOf("ONLY_EXPECTED" to "yes"),
            ),
        )

        process.use {
            assertEquals(0, it.awaitExit(2.seconds))
            assertEquals("ONLY_EXPECTED=yes", it.standardOutput.bufferedReader().readText().trim())
            assertTrue(it.standardError.bufferedReader().readText().isEmpty())
        }
    }
}
