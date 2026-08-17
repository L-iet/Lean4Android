package org.lean4android.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.lean4android.process.ProcessResult

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
}
