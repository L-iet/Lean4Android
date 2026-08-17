package org.lean4android.app

import androidx.compose.ui.text.TextRange
import org.lean4android.lsp.JsonValueParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeanEditorEngineTest {
    @Test fun `definition locations accept standard location and location link responses`() {
        val response = JsonValueParser.parse(
            """[{"uri":"file:///standard.lean","range":{"start":{"line":2,"character":3},"end":{"line":2,"character":4}}},{"targetUri":"file:///linked.lean","targetRange":{"start":{"line":8,"character":1},"end":{"line":8,"character":9}},"targetSelectionRange":{"start":{"line":8,"character":4},"end":{"line":8,"character":7}}}]""",
        )

        assertEquals(
            listOf("file:///standard.lean" to (2 to 3), "file:///linked.lean" to (8 to 4)),
            lspLocations(response),
        )
    }

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

    @Test fun `diagnostic decoration is bounded and preserves source`() {
        val source = "example : True := by trivial\n"
        val range = TextRange(10, 14)
        val highlighted = leanHighlightedText(source, diagnosticRanges = listOf(range, TextRange(500, 600)))
        assertEquals(source, highlighted.text)
        assertTrue(highlighted.spanStyles.any {
            it.start == range.start && it.end == range.end && it.item.textDecoration != null
        })
    }

    @Test fun `highlighter registry uses plain fallback without Lean token decoration`() {
        val source = "def plain text 42"
        val plain = plainHighlightedText(source)

        assertEquals(source, plain.text)
        assertTrue(plain.spanStyles.isEmpty())
        assertTrue(EditorHighlighterRegistry.visualTransformation("notes.txt") is PlainTextVisualTransformation)
        assertTrue(EditorHighlighterRegistry.visualTransformation("Main.lean") is LeanSyntaxVisualTransformation)
        assertTrue(plainHighlightedText(source, "plain").spanStyles.isNotEmpty())
    }

    @Test fun `LSP positions use zero based lines and UTF-16 code units`() {
        val source = "α🙂x\nsecond"
        assertEquals(LspPosition(0, 0), lspPositionAt(source, 0))
        assertEquals(LspPosition(0, 3), lspPositionAt(source, 3)) // α=1, emoji=2 UTF-16 units
        assertEquals(LspPosition(1, 0), lspPositionAt(source, 5))
        assertEquals(LspPosition(1, 6), lspPositionAt(source, source.length))
        assertEquals(3, offsetAtLspPosition(source, LspPosition(0, 3)))
        assertEquals(5, offsetAtLspPosition(source, LspPosition(1, 0)))
        assertEquals(source.length, offsetAtLspPosition(source, LspPosition(9, 0)))
    }
}
