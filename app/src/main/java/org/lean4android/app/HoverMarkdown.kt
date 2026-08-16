package org.lean4android.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.lean4android.app.ui.theme.LeanTheme

internal sealed interface HoverMarkdownBlock {
    data class Heading(val level: Int, val text: String) : HoverMarkdownBlock
    data class Paragraph(val text: String) : HoverMarkdownBlock
    data class ListItem(val text: String) : HoverMarkdownBlock
    data class Code(val language: String, val source: String) : HoverMarkdownBlock
}

/** A deliberately bounded local Markdown subset; hover text never loads or executes external content. */
internal fun parseHoverMarkdown(markdown: String): List<HoverMarkdownBlock> {
    val lines = markdown.take(64 * 1024).lines()
    val blocks = mutableListOf<HoverMarkdownBlock>()
    val paragraph = mutableListOf<String>()
    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += HoverMarkdownBlock.Paragraph(paragraph.joinToString(" ").trim())
            paragraph.clear()
        }
    }
    var index = 0
    while (index < lines.size && blocks.size < 256) {
        val line = lines[index]
        if (line.startsWith("```")) {
            flushParagraph()
            val language = line.removePrefix("```").trim().lowercase()
            val code = mutableListOf<String>()
            index++
            while (index < lines.size && !lines[index].startsWith("```") && code.size < 2_000) {
                code += lines[index++]
            }
            blocks += HoverMarkdownBlock.Code(language, code.joinToString("\n"))
        } else {
            val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(line)
            val list = Regex("^\\s*[-*+]\\s+(.+)$").matchEntire(line)
            when {
                line.isBlank() -> flushParagraph()
                heading != null -> {
                    flushParagraph()
                    blocks += HoverMarkdownBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
                }
                list != null -> {
                    flushParagraph()
                    blocks += HoverMarkdownBlock.ListItem(list.groupValues[1])
                }
                else -> paragraph += line.trim()
            }
        }
        index++
    }
    flushParagraph()
    return blocks.take(256)
}

internal fun hoverInlineMarkdown(
    source: String,
    codeColor: Color = Color(0xff7b1fa2),
    linkColor: Color = Color(0xff1565c0),
): AnnotatedString = buildAnnotatedString {
    val token = Regex("(`[^`]+`|\\*\\*[^*]+\\*\\*|__[^_]+__|(?<!\\*)\\*[^*]+\\*(?!\\*)|(?<!_)_[^_]+_(?!_)|\\[[^]]+]\\([^)]+\\))")
    var cursor = 0
    token.findAll(source).forEach { match ->
        append(source.substring(cursor, match.range.first))
        val raw = match.value
        when {
            raw.startsWith('`') -> withStyle(SpanStyle(color = codeColor, fontFamily = FontFamily.Monospace)) {
                append(raw.substring(1, raw.length - 1))
            }
            raw.startsWith("**") || raw.startsWith("__") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(raw.substring(2, raw.length - 2))
            }
            raw.startsWith('*') || raw.startsWith('_') -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(raw.substring(1, raw.length - 1))
            }
            raw.startsWith('[') -> {
                val labelEnd = raw.indexOf(']')
                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                    append(raw.substring(1, labelEnd))
                }
            }
        }
        cursor = match.range.last + 1
    }
    append(source.substring(cursor))
}

@Composable
internal fun HoverMarkdown(markdown: String) {
    val blocks = parseHoverMarkdown(markdown)
    val style = LeanTheme.components.hover
    val dimensions = LeanTheme.dimensions
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(dimensions.hoverBlockSpacing)) {
            blocks.forEach { block ->
                when (block) {
                    is HoverMarkdownBlock.Heading -> Text(
                        hoverInlineMarkdown(block.text, linkColor = style.linkColor),
                        style = if (block.level <= 2) style.largeHeadingStyle else style.smallHeadingStyle,
                    )
                    is HoverMarkdownBlock.Paragraph -> Text(
                        hoverInlineMarkdown(block.text, linkColor = style.linkColor),
                        style = style.bodyStyle,
                    )
                    is HoverMarkdownBlock.ListItem -> Text(
                        hoverInlineMarkdown("• ${block.text}", linkColor = style.linkColor),
                        style = style.bodyStyle,
                    )
                    is HoverMarkdownBlock.Code -> Text(
                        if (block.language == "lean" || block.language == "lean4") leanHighlightedText(block.source)
                        else AnnotatedString(block.source),
                        modifier = Modifier.fillMaxWidth().background(style.codeContainerColor).padding(dimensions.hoverCodePadding),
                        color = style.codeContentColor,
                        style = style.codeStyle,
                    )
                }
            }
        }
    }
}
