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

    private class ClosingSource : InputByteSource {
        override val expectedBytes = 0L
        var closed = false
        override fun openStream() = ByteArrayInputStream(byteArrayOf())
        override fun close() { closed = true }
    }
}
