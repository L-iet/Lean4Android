package org.lean4android.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.lean4android.lsp.JsonValue

internal data class CompletionCandidate(
    val label: String,
    val detail: String? = null,
    val insertion: String = label,
    val editRange: Pair<LspPosition, LspPosition>? = null,
)

internal const val MAX_COMPLETION_SUGGESTIONS = 5

internal fun parseCompletionCandidates(
    result: JsonValue?,
    prefix: String? = null,
    limit: Int = MAX_COMPLETION_SUGGESTIONS,
): List<CompletionCandidate> {
    val values = when (result) {
        is JsonValue.ArrayValue -> result.values
        is JsonValue.ObjectValue -> (result.fields["items"] as? JsonValue.ArrayValue)?.values.orEmpty()
        else -> emptyList()
    }
    val candidates = values.mapNotNull { raw ->
        val item = raw as? JsonValue.ObjectValue ?: return@mapNotNull null
        val label = (item.fields["label"] as? JsonValue.StringValue)?.value?.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        val detail = (item.fields["detail"] as? JsonValue.StringValue)?.value?.takeIf(String::isNotBlank)
        val snippet = (item.fields["insertTextFormat"] as? JsonValue.NumberValue)?.source == "2"
        val textEdit = item.fields["textEdit"] as? JsonValue.ObjectValue
        val replacement = (textEdit?.fields?.get("newText") as? JsonValue.StringValue)?.value
            ?: (item.fields["insertText"] as? JsonValue.StringValue)?.value
            ?: label
        val range = textEdit?.fields?.get("range") ?: textEdit?.fields?.get("insert")
        CompletionCandidate(
            label = label,
            detail = detail,
            insertion = if (snippet) label else replacement,
            editRange = parseCompletionRange(range),
        )
    }.distinctBy { listOf(it.label, it.insertion, it.editRange) }
    val typedPrefix = prefix?.takeIf(String::isNotBlank)
    val ranked = if (typedPrefix == null) candidates else candidates
        .filter { it.label.contains(typedPrefix, ignoreCase = true) }
        .sortedBy { if (it.label.startsWith(typedPrefix, ignoreCase = true)) 0 else 1 }
    return ranked.take(limit.coerceIn(1, MAX_COMPLETION_SUGGESTIONS))
}

private fun parseCompletionRange(value: JsonValue?): Pair<LspPosition, LspPosition>? {
    val range = value as? JsonValue.ObjectValue ?: return null
    fun position(key: String): LspPosition? {
        val point = range.fields[key] as? JsonValue.ObjectValue ?: return null
        val line = (point.fields["line"] as? JsonValue.NumberValue)?.source?.toIntOrNull() ?: return null
        val character = (point.fields["character"] as? JsonValue.NumberValue)?.source?.toIntOrNull() ?: return null
        return if (line >= 0 && character >= 0) LspPosition(line, character) else null
    }
    return position("start")?.let { start -> position("end")?.let { end -> start to end } }
}

internal fun applyCompletion(value: TextFieldValue, candidate: CompletionCandidate): TextFieldValue {
    val caret = value.selection.end.coerceIn(0, value.text.length)
    val range = candidate.editRange?.let { (start, end) ->
        offsetAtLspPosition(value.text, start)..offsetAtLspPosition(value.text, end)
    }?.takeIf { it.first <= it.last && caret in it.first..it.last }
    val start = range?.first ?: value.text.take(caret).indexOfLast { !it.isLetterOrDigit() && it != '_' && it != '\'' } + 1
    val end = range?.last ?: caret
    val updated = value.text.replaceRange(start, end, candidate.insertion)
    val newCaret = start + candidate.insertion.length
    return TextFieldValue(updated, TextRange(newCaret))
}

internal fun completionPrefixEligible(value: TextFieldValue): Boolean {
    if (!value.selection.collapsed || value.composition != null) return false
    val previous = value.text.getOrNull(value.selection.end - 1) ?: return false
    return previous.isLetterOrDigit() || previous == '_' || previous == '\''
}

internal fun completionPrefix(text: String, offset: Int): String {
    val caret = offset.coerceIn(0, text.length)
    val start = text.take(caret).indexOfLast { !it.isLetterOrDigit() && it != '_' && it != '\'' } + 1
    return text.substring(start, caret)
}

internal fun isCompletionTypingChange(previous: TextFieldValue, current: TextFieldValue): Boolean =
    current.text != previous.text || (previous.composition != null && current.composition == null)
