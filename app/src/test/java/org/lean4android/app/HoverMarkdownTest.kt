package org.lean4android.app

import org.lean4android.lsp.JsonValueParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoverMarkdownTest {
    @Test fun `markdown parser separates headings lists paragraphs and fenced Lean`() {
        val blocks = parseHoverMarkdown("""# Definition

Use **this** term with `Nat`.

- first
- second

```lean
theorem demo : True := by trivial
```
""")
        assertEquals(HoverMarkdownBlock.Heading(1, "Definition"), blocks[0])
        assertTrue(blocks[1] is HoverMarkdownBlock.Paragraph)
        assertEquals(2, blocks.count { it is HoverMarkdownBlock.ListItem })
        assertEquals("lean", (blocks.last() as HoverMarkdownBlock.Code).language)
        assertTrue((blocks.last() as HoverMarkdownBlock.Code).source.contains("theorem demo"))
        assertTrue(leanHighlightedText((blocks.last() as HoverMarkdownBlock.Code).source).spanStyles.isNotEmpty())
    }

    @Test fun `inline renderer removes markup and preserves readable labels`() {
        val rendered = hoverInlineMarkdown("Use **bold**, *emphasis*, `Nat`, and [manual](https://example.invalid).")
        assertEquals("Use bold, emphasis, Nat, and manual.", rendered.text)
        assertTrue(rendered.spanStyles.isNotEmpty())
        assertFalse(rendered.text.contains("https://"))
    }

    @Test fun `goal sections independently show tactic expected both and neither`() {
        assertEquals(listOf("Goals" to "h : P\n⊢ P"), goalPaneSections("h : P\n⊢ P", null))
        assertEquals(listOf("Expected type" to "Nat"), goalPaneSections(null, "Nat"))
        assertEquals(listOf("Goals" to "⊢ P", "Expected type" to "P"), goalPaneSections("⊢ P", "P"))
        assertEquals(listOf(null to "No goals"), goalPaneSections("No goals", ""))
    }

    @Test fun `pinned plain term goal response reads goal and accepts null`() {
        assertEquals("⊢ Nat", plainTermGoalText(JsonValueParser.parse("""{"goal":"⊢ Nat","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}}""")))
        assertEquals("", plainTermGoalText(JsonValueParser.parse("null")))
    }

    @Test fun `plain tactic goal uses structured goals without Markdown fences`() {
        val response = JsonValueParser.parse("""{"rendered":"```lean\\n⊢ True\\n```","goals":["⊢ True"]}""")
        assertEquals("⊢ True", plainGoalText(response))
        assertFalse(plainGoalText(response).contains("```"))
    }
}
