package org.lean4android.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration

internal class EditorUndoHistory(
    initial: String,
    private val capacity: Int = 100,
) {
    init {
        require(capacity >= 2) { "Undo capacity must be at least two" }
    }

    private val entries = mutableListOf(initial)
    private var index = 0

    val canUndo: Boolean get() = index > 0
    val canRedo: Boolean get() = index < entries.lastIndex

    fun record(value: String) {
        if (entries[index] == value) return
        if (index < entries.lastIndex) entries.subList(index + 1, entries.size).clear()
        entries += value
        index++
        if (entries.size > capacity) {
            entries.removeAt(0)
            index--
        }
    }

    fun undo(): String? = if (!canUndo) null else entries[--index]

    fun redo(): String? = if (!canRedo) null else entries[++index]
}

internal fun findEditorMatches(source: String, query: String): List<TextRange> {
    if (query.isEmpty()) return emptyList()
    val matches = mutableListOf<TextRange>()
    var from = 0
    while (from <= source.length - query.length) {
        val index = source.indexOf(query, startIndex = from, ignoreCase = true)
        if (index < 0) break
        matches += TextRange(index, index + query.length)
        from = index + maxOf(query.length, 1)
    }
    return matches
}

internal fun nextEditorMatch(matches: List<TextRange>, selection: TextRange, backwards: Boolean): TextRange? {
    if (matches.isEmpty()) return null
    return if (backwards) {
        matches.lastOrNull { it.end < selection.end } ?: matches.last()
    } else {
        matches.firstOrNull { it.start > selection.start } ?: matches.first()
    }
}

internal data class LspPosition(val line: Int, val character: Int)

/** Compose and LSP both index Java/Kotlin strings in UTF-16 code units. */
internal fun lspPositionAt(source: String, offset: Int): LspPosition {
    val safeOffset = offset.coerceIn(0, source.length)
    val lineStart = source.lastIndexOf('\n', startIndex = (safeOffset - 1).coerceAtLeast(0))
        .let { if (it < 0 || safeOffset == 0) 0 else it + 1 }
    return LspPosition(
        line = source.take(safeOffset).count { it == '\n' },
        character = safeOffset - lineStart,
    )
}

internal fun offsetAtLspPosition(source: String, position: LspPosition): Int {
    require(position.line >= 0 && position.character >= 0) { "LSP position cannot be negative" }
    var line = 0
    var lineStart = 0
    while (line < position.line) {
        val newline = source.indexOf('\n', lineStart)
        if (newline < 0) return source.length
        lineStart = newline + 1
        line++
    }
    val lineEnd = source.indexOf('\n', lineStart).let { if (it < 0) source.length else it }
    return (lineStart + position.character).coerceAtMost(lineEnd)
}

internal class LeanSyntaxVisualTransformation(
    private val searchQuery: String = "",
    private val diagnosticRanges: List<TextRange> = emptyList(),
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText = TransformedText(
        leanHighlightedText(text.text, searchQuery, diagnosticRanges),
        OffsetMapping.Identity,
    )
}

internal fun leanHighlightedText(
    source: String,
    searchQuery: String = "",
    diagnosticRanges: List<TextRange> = emptyList(),
): AnnotatedString = buildAnnotatedString {
    append(source)
    LEAN_TOKEN_RULES.forEach { (pattern, style) ->
        pattern.findAll(source).forEach { match ->
            addStyle(style, match.range.first, match.range.last + 1)
        }
    }
    findEditorMatches(source, searchQuery).forEach { match ->
        addStyle(SEARCH_STYLE, match.start, match.end)
    }
    diagnosticRanges.forEach { range ->
        val start = range.start.coerceIn(0, source.length)
        val end = range.end.coerceIn(start, source.length)
        if (start < end) addStyle(DIAGNOSTIC_STYLE, start, end)
    }
}

private val LEAN_TOKEN_RULES = listOf(
    Regex("(?m)^\\s*#(?:check|eval|print|reduce|synth)\\b") to SpanStyle(color = Color(0xff8e24aa)),
    Regex("\\b(?:namespace|end|import|def|theorem|example|structure|inductive|where|by|let|in|match|with|if|then|else|fun|forall|opaque|abbrev|class|instance)\\b") to
        SpanStyle(color = Color(0xff1565c0)),
    Regex("\\b(?:Nat|Int|String|Bool|Prop|Type|true|false)\\b") to SpanStyle(color = Color(0xffad5f00)),
    Regex("\\b\\d+\\b") to SpanStyle(color = Color(0xffc62828)),
    Regex("\\\"(?:\\\\.|[^\\\"\\\\])*\\\"") to SpanStyle(color = Color(0xff2e7d32)),
    Regex("--[^\\n]*") to SpanStyle(color = Color(0xff6d7580)),
)

private val SEARCH_STYLE = SpanStyle(
    color = Color(0xff111111),
    background = Color(0xffffd54f),
)

private val DIAGNOSTIC_STYLE = SpanStyle(
    color = Color(0xffb3261e),
    textDecoration = TextDecoration.Underline,
)
