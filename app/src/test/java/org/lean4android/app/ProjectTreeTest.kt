package org.lean4android.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTreeTest {
    @Test fun `tree compacts single-child chains and preserves branching hierarchy`() {
        val paths = listOf("A/B/C.txt", "Src/Main.lean", "Src/Util/One.lean", "Src/Util/Two.lean")
        val collapsed = projectTreeRows(paths, emptySet())
        assertEquals("A/B/C.txt", collapsed.first().label)
        assertEquals(ProjectEntryIcon.File, collapsed.first().icon)
        assertEquals("Src", collapsed[1].label)
        assertTrue(collapsed[1].expandable)

        val expanded = projectTreeRows(paths, setOf("Src", "Src/Util"))
        assertEquals(listOf("A/B/C.txt", "Src", "Main.lean", "Util", "One.lean", "Two.lean"), expanded.map { it.label })
        assertEquals(listOf(0, 0, 1, 1, 2, 2), expanded.map { it.depth })
        assertEquals(ProjectEntryIcon.LeanFile, expanded[2].icon)
    }

    @Test fun `symbol insertion replaces either selection direction and advances cursor`() {
        val forward = insertEditorSymbol(TextFieldValue("aXXb", TextRange(1, 3)), "∀")
        assertEquals("a∀b", forward.text)
        assertEquals(TextRange(2), forward.selection)

        val reverse = insertEditorSymbol(TextFieldValue("ab", TextRange(2, 1)), "→")
        assertEquals("a→", reverse.text)
        assertEquals(TextRange(2), reverse.selection)
        assertFalse(reverse.selection.collapsed.not())
    }
}
