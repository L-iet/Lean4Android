package org.lean4android.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSymbolPreferenceTest {
    @Test fun `missing and invalid preferences restore defaults`() {
        assertEquals(DEFAULT_EDITOR_SYMBOLS, editorSymbolsFromPreference(null))
        assertEquals(DEFAULT_EDITOR_SYMBOLS, editorSymbolsFromPreference("\n\n"))
        assertEquals(DEFAULT_EDITOR_SYMBOLS, editorSymbolsFromPreference((1..61).joinToString("\n")))
    }

    @Test fun `ordered symbols round trip`() {
        val symbols = listOf("→", "by", "⟨_⟩")
        assertEquals(symbols, editorSymbolsFromPreference(editorSymbolsPreference(symbols)))
    }

    @Test fun `validation trims blanks and enforces bounds`() {
        assertEquals(listOf("∀", "∃"), validateEditorSymbols(" ∀ \n\n ∃ ").getOrThrow())
        assertTrue(validateEditorSymbols((1..61).joinToString("\n")).isFailure)
        assertTrue(validateEditorSymbols("abcdefghijklmnopq").isFailure)
    }

    @Test fun `font preferences accept only documented fixed choices`() {
        EDITOR_FONT_SIZES_SP.forEach { assertEquals(it, editorFontSizeFromPreference(it)) }
        INTERFACE_FONT_PERCENTAGES.forEach { assertEquals(it, interfaceFontPercentFromPreference(it)) }
        assertEquals(16, editorFontSizeFromPreference(17))
        assertEquals(100, interfaceFontPercentFromPreference(101))
    }
}
