package org.lean4android.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.lean4android.lsp.JsonValue

class LspCompletionTest {
    @Test fun parsesListAndTextEditWhileRefusingSnippetExpansion() {
        val result = JsonValue.ObjectValue(mapOf("items" to JsonValue.ArrayValue(listOf(
            item("answer", "answer_is_positive", 0, 4),
            JsonValue.ObjectValue(mapOf(
                "label" to JsonValue.StringValue("by snippet"),
                "insertText" to JsonValue.StringValue("by\n  ${'$'}0"),
                "insertTextFormat" to JsonValue.NumberValue("2"),
            )),
        ))))
        val parsed = parseCompletionCandidates(result)
        assertEquals("answer_is_positive", parsed[0].insertion)
        assertEquals(LspPosition(0, 0) to LspPosition(0, 4), parsed[0].editRange)
        assertEquals("by snippet", parsed[1].insertion)
    }

    @Test fun appliesServerRangeOrIdentifierPrefixAsOneSelectionEdit() {
        val value = TextFieldValue("ans tail", TextRange(3))
        assertEquals("answer tail", applyCompletion(value, CompletionCandidate("answer", insertion = "answer")).text)
        val ranged = CompletionCandidate("answer", insertion = "answer", editRange = LspPosition(0, 0) to LspPosition(0, 3))
        assertEquals(TextFieldValue("answer tail", TextRange(6)), applyCompletion(value, ranged))
    }

    @Test fun retainsAtMostFiveDistinctSuggestions() {
        val result = JsonValue.ArrayValue((1..9).map { index ->
            JsonValue.ObjectValue(mapOf("label" to JsonValue.StringValue("candidate$index")))
        })
        assertEquals(MAX_COMPLETION_SUGGESTIONS, parseCompletionCandidates(result).size)
    }

    @Test fun automaticRequestRequiresCollapsedIdentifierPrefix() {
        assertEquals(true, completionPrefixEligible(TextFieldValue("ans", TextRange(3))))
        assertEquals(false, completionPrefixEligible(TextFieldValue("ans ", TextRange(4))))
        assertEquals(false, completionPrefixEligible(TextFieldValue("ans", TextRange(0, 3))))
    }

    private fun item(label: String, replacement: String, start: Int, end: Int) = JsonValue.ObjectValue(mapOf(
        "label" to JsonValue.StringValue(label),
        "textEdit" to JsonValue.ObjectValue(mapOf(
            "newText" to JsonValue.StringValue(replacement),
            "range" to JsonValue.ObjectValue(mapOf(
                "start" to position(start),
                "end" to position(end),
            )),
        )),
    ))

    private fun position(character: Int) = JsonValue.ObjectValue(mapOf(
        "line" to JsonValue.NumberValue("0"),
        "character" to JsonValue.NumberValue(character.toString()),
    ))
}
