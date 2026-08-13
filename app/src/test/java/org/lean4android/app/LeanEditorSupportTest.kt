package org.lean4android.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.lean4android.process.ProcessResult
import kotlin.time.Duration.Companion.milliseconds

class LeanEditorSupportTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `atomic write creates and replaces Lean source`() {
        val source = temporaryFolder.root.resolve("project/Main.lean")

        writeAtomically(source, "#check Nat")
        writeAtomically(source, "#eval 1 + 1")

        assertEquals("#eval 1 + 1", source.readText())
        assertFalse(source.resolveSibling("Main.lean.saving").exists())
    }

    @Test
    fun `result formatting keeps process status and both streams`() {
        val output = formatResult(
            ProcessResult(
                exitCode = 1,
                stdout = "one_plus_one : 1 + 1 = 2\n",
                stderr = "Main.lean:2: error: example\n",
                timedOut = false,
            ),
            915.milliseconds,
        )

        assertTrue(output.contains("Exit: 1 • 915 ms"))
        assertTrue(output.contains("stdout:\none_plus_one"))
        assertTrue(output.contains("stderr:\nMain.lean:2: error: example"))
    }

    @Test
    fun `timeout is visible even without process output`() {
        val output = formatResult(
            ProcessResult(exitCode = -1, stdout = "", stderr = "", timedOut = true),
            30_000.milliseconds,
        )

        assertTrue(output.contains("Exit: -1 (timed out)"))
        assertTrue(output.contains("Lean produced no output."))
    }
}
