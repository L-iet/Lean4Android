package org.lean4android.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lean4android.process.InputByteSource
import org.lean4android.process.ProcessResult
import org.lean4android.process.StdinPlan
import java.io.ByteArrayInputStream

class ProjectRunSequenceTest {
    @Test fun `combined result retains program status and ordered build output`() {
        val build = ProcessResult(0, "built", "build warning\n", false)
        val program = ProcessResult(7, "answer\n", "program error", true)

        val combined = combineProjectRunResult(build, program, "Main.lean")

        assertEquals(7, combined.exitCode)
        assertEquals("built\nBuild completed; running Main.lean\nanswer\n", combined.stdout)
        assertEquals("build warning\nprogram error", combined.stderr)
        assertEquals(true, combined.timedOut)
    }

    @Test fun `run input metadata is explicit and unused byte sources are closeable`() {
        assertEquals(ProjectRunInput.ImmediateEof, defaultRunInput(StdinPlan.ImmediateEof))
        assertEquals(ProjectRunInput.Interactive, defaultRunInput(StdinPlan.Interactive))
        val source = ClosingSource()

        closeRunStdinPlan(StdinPlan.Bytes(source))

        assertTrue(source.closed)
    }

    @Test fun `saved-version project input is excluded from pre-run saves`() {
        val editor = EditorSessionState(
            "sample",
            listOf(
                EditorTab("Main.lean", "old main", "new main"),
                EditorTab("input.txt", "saved bytes", "dirty bytes"),
            ),
            "Main.lean",
        )

        val savedVersion = sourcesToSaveForRun(
            editor, RunInputSelection.ProjectFile("input.txt", useSavedVersion = true),
        )
        val saveAndRun = sourcesToSaveForRun(
            editor, RunInputSelection.ProjectFile("input.txt", useSavedVersion = false),
        )

        assertEquals(mapOf("Main.lean" to "new main"), savedVersion)
        assertEquals(mapOf("Main.lean" to "new main", "input.txt" to "dirty bytes"), saveAndRun)
    }

    private class ClosingSource : InputByteSource {
        override val expectedBytes = 0L
        var closed = false
        override fun openStream() = ByteArrayInputStream(byteArrayOf())
        override fun close() { closed = true }
    }
}
