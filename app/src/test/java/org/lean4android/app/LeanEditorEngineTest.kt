package org.lean4android.app

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeanEditorEngineTest {
    @Test fun `bounded undo redo discards redo branch after new edit`() {
        val history = EditorUndoHistory("zero", capacity = 3)
        history.record("one")
        history.record("two")
        history.record("three")
        assertEquals("two", history.undo())
        assertEquals("one", history.undo())
        assertNull(history.undo())
        assertEquals("two", history.redo())
        history.record("replacement")
        assertFalse(history.canRedo)
        assertEquals("two", history.undo())
    }

    @Test fun `search is case insensitive Unicode safe and wraps in both directions`() {
        val source = "α Lean 🙂 lean LEAN"
        val matches = findEditorMatches(source, "lean")
        assertEquals(listOf("Lean", "lean", "LEAN"), matches.map { source.substring(it.start, it.end) })
        assertEquals(matches[1], nextEditorMatch(matches, matches[0], backwards = false))
        assertEquals(matches[0], nextEditorMatch(matches, matches[1], backwards = true))
        assertEquals(matches[0], nextEditorMatch(matches, matches.last(), backwards = false))
        assertEquals(matches.last(), nextEditorMatch(matches, matches.first(), backwards = true))
        assertTrue(findEditorMatches(source, "🙂").single().let { source.substring(it.start, it.end) } == "🙂")
    }

    @Test fun `syntax and search decoration preserve source offsets`() {
        val source = "import Demo\n-- comment\ndef answer : Nat := 42\n#eval answer\n"
        val highlighted = leanHighlightedText(source, "answer")
        assertEquals(source, highlighted.text)
        assertTrue(highlighted.spanStyles.isNotEmpty())
        assertTrue(highlighted.spanStyles.all { it.start >= 0 && it.end <= source.length && it.start < it.end })
        val answerRanges = findEditorMatches(source, "answer")
        assertEquals(2, answerRanges.size)
        assertNotEquals(
            highlighted.spanStyles.first { it.start == answerRanges.first().start && it.end == answerRanges.first().end }.item.background,
            androidx.compose.ui.graphics.Color.Unspecified,
        )
    }
}
