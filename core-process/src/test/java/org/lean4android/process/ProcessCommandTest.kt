package org.lean4android.process

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProcessCommandTest {
    @Test
    fun `command retains arguments as distinct values`() {
        val command = ProcessCommand(
            executable = File("/installed/lib/liblean_exe.so"),
            arguments = listOf("file with spaces.lean", "a;not-a-shell-command"),
            workingDirectory = File("/data/project"),
        )

        assertEquals(2, command.arguments.size)
        assertEquals("a;not-a-shell-command", command.arguments.last())
    }

    @Test
    fun `command rejects relative executable`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProcessCommand(
                executable = File("lean"),
                workingDirectory = File("/data/project"),
            )
        }
    }

    @Test
    fun `command rejects nul in argument`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProcessCommand(
                executable = File("/installed/lib/liblean_exe.so"),
                arguments = listOf("bad\u0000argument"),
                workingDirectory = File("/data/project"),
            )
        }
    }
}

