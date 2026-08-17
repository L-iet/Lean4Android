package org.lean4android.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.lean4android.process.ProcessResult
import org.lean4android.process.CapturedOutput
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

    @Test
    fun `result formatting discloses independent stream truncation`() {
        val output = formatResult(
            ProcessResult(
                0, "out", "err", false,
                CapturedOutput.fromBytes("out".toByteArray(), omittedBytes = 7),
                CapturedOutput.fromBytes("err".toByteArray(), omittedBytes = 11),
            ),
            1.milliseconds,
        )

        assertTrue(output.contains("stdout truncated; 7 bytes omitted"))
        assertTrue(output.contains("stderr truncated; 11 bytes omitted"))
    }

    @Test
    fun `integrity result distinguishes success from reported corruption`() {
        val success = formatIntegrityResult(emptyList(), 1_234.milliseconds)
        val failure = formatIntegrityResult(listOf("Runtime file hash mismatch: lib/lean/Init.olean"), 5.milliseconds)

        assertTrue(success.contains("Runtime integrity • 1234 ms"))
        assertTrue(success.contains("All packaged runtime files match"))
        assertTrue(failure.contains("found 1 problem(s)"))
        assertTrue(failure.contains("Init.olean"))
    }

    @Test
    fun `line numbers cover empty text and every logical line`() {
        assertEquals("1", editorLineNumbers(""))
        assertEquals("1\n2\n3", editorLineNumbers("first\nsecond\n"))
    }
}
