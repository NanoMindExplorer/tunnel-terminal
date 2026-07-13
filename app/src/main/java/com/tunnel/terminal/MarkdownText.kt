package com.tunnel.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MarkdownText - Render markdown text di Compose.
 *
 * Phase 22: Markdown rendering untuk AI responses.
 * AI sering balas dengan markdown (```code```, **bold**, ## headers, lists).
 * Tanpa renderer, user lihat raw markdown syntax — jelek.
 *
 * Supported:
 * - Headers: # ## ### ####
 * - Bold: **text**
 * - Italic: *text*
 * - Inline code: `code`
 * - Code blocks: ```lang\ncode\n```
 * - Lists: - item, * item, 1. item
 * - Links: [text](url)
 * - Blockquotes: > text
 *
 * Markdown renderer for AI responses.
 */
@Composable
fun MarkdownText(
    markdown: String,
    theme: TerminalTheme,
    modifier: Modifier = Modifier,
    fontSize: Int = 13
) {
    val syntaxColors = SyntaxHighlighter.colorsFromTheme(theme)
    val blocks = parseMarkdown(markdown)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Header -> {
                    val (size, weight) = when (block.level) {
                        1 -> 20 to FontWeight.Bold
                        2 -> 17 to FontWeight.Bold
                        3 -> 15 to FontWeight.Bold
                        else -> 14 to FontWeight.Bold
                    }
                    Text(
                        text = block.text,
                        color = theme.ansi.getOrElse(4) { Color(0xFF2196F3) },
                        fontSize = size.sp,
                        fontWeight = weight,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                }
                is MarkdownBlock.CodeBlock -> {
                    /* Code block dengan syntax highlighting + Wave-17 copy. */
                    val language = block.language.ifBlank { "plain" }
                    val highlighted = SyntaxHighlighter.highlight(block.code, language, syntaxColors)
                    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                    val context = androidx.compose.ui.platform.LocalContext.current
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(theme.background.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                            .padding(8.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (block.language.isNotBlank()) block.language else "code",
                                    color = theme.uiTextMuted,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    "Copy",
                                    color = theme.uiAccent,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier
                                        .clickable {
                                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(block.code))
                                            android.widget.Toast.makeText(
                                                context,
                                                "Kode disalin",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        .padding(horizontal = 4.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = highlighted,
                                color = theme.foreground,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
                is MarkdownBlock.Blockquote -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(theme.uiSurface.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 8.dp)
                    ) {
                        Text(
                            text = block.text,
                            color = theme.uiTextMuted,
                            fontSize = fontSize.sp,
                            fontStyle = FontStyle.Italic,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                is MarkdownBlock.ListItem -> {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (block.ordered) "${block.index}." else "•",
                            color = theme.ansi.getOrElse(6) { Color(0xFF00BCD4) },
                            fontSize = fontSize.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(20.dp)
                        )
                        Text(
                            text = renderInlineMarkdown(block.text, theme),
                            fontSize = fontSize.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = renderInlineMarkdown(block.text, theme),
                        color = theme.foreground,
                        fontSize = fontSize.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                is MarkdownBlock.HorizontalRule -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(theme.uiSurface)
                    )
                }
            }
        }
    }
}

/** Sealed class untuk markdown block types. */
sealed class MarkdownBlock {
    data class Header(val text: String, val level: Int) : MarkdownBlock()
    data class CodeBlock(val code: String, val language: String) : MarkdownBlock()
    data class Blockquote(val text: String) : MarkdownBlock()
    data class ListItem(val text: String, val ordered: Boolean, val index: Int) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
    object HorizontalRule : MarkdownBlock()
}

/** Parse markdown string ke list of blocks. */
private fun parseMarkdown(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = text.lines()
    var i = 0
    var listIndex = 0

    while (i < lines.size) {
        val line = lines[i]

        when {
            /* Code block: ```lang ... ``` */
            line.startsWith("```") -> {
                val language = line.removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                blocks.add(MarkdownBlock.CodeBlock(codeLines.joinToString("\n"), language))
                if (i < lines.size) i++ /* skip closing ``` */
            }
            /* Header: # ## ### */
            line.startsWith("# ") -> {
                blocks.add(MarkdownBlock.Header(line.removePrefix("# ").trim(), 1))
                i++
            }
            line.startsWith("## ") -> {
                blocks.add(MarkdownBlock.Header(line.removePrefix("## ").trim(), 2))
                i++
            }
            line.startsWith("### ") -> {
                blocks.add(MarkdownBlock.Header(line.removePrefix("### ").trim(), 3))
                i++
            }
            line.startsWith("#### ") -> {
                blocks.add(MarkdownBlock.Header(line.removePrefix("#### ").trim(), 4))
                i++
            }
            /* Horizontal rule: --- or *** */
            line.matches(Regex("^(---|\\*\\*\\*)\\s*$")) -> {
                blocks.add(MarkdownBlock.HorizontalRule)
                i++
            }
            /* Blockquote: > text */
            line.startsWith("> ") -> {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].startsWith("> ")) {
                    quoteLines.add(lines[i].removePrefix("> "))
                    i++
                }
                blocks.add(MarkdownBlock.Blockquote(quoteLines.joinToString("\n")))
            }
            /* Ordered list: 1. item */
            line.matches(Regex("^\\d+\\.\\s+.+")) -> {
                listIndex = 1
                while (i < lines.size && lines[i].matches(Regex("^\\d+\\.\\s+.+"))) {
                    val itemText = lines[i].substringAfter(". ").trim()
                    blocks.add(MarkdownBlock.ListItem(itemText, ordered = true, index = listIndex))
                    listIndex++
                    i++
                }
            }
            /* Unordered list: - item or * item */
            line.matches(Regex("^[-*]\\s+.+")) && !line.startsWith("```") -> {
                while (i < lines.size && lines[i].matches(Regex("^[-*]\\s+.+"))) {
                    val itemText = lines[i].substring(2).trim()
                    blocks.add(MarkdownBlock.ListItem(itemText, ordered = false, index = 0))
                    i++
                }
            }
            /* Empty line — skip. */
            line.isBlank() -> i++
            /* Paragraph (default). */
            else -> {
                val paraLines = mutableListOf<String>()
                while (i < lines.size && lines[i].isNotBlank() &&
                       !lines[i].startsWith("#") && !lines[i].startsWith("```") &&
                       !lines[i].startsWith("> ") && !lines[i].matches(Regex("^[-*]\\s+.+")) &&
                       !lines[i].matches(Regex("^\\d+\\.\\s+.+"))) {
                    paraLines.add(lines[i])
                    i++
                }
                if (paraLines.isNotEmpty()) {
                    blocks.add(MarkdownBlock.Paragraph(paraLines.joinToString(" ")))
                }
            }
        }
    }

    return blocks
}

/** Render inline markdown (bold, italic, code, links) ke AnnotatedString. */
private fun renderInlineMarkdown(text: String, theme: TerminalTheme): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        val codeColor = theme.ansi.getOrElse(2) { Color(0xFF4CAF50) }
        val boldColor = theme.ansi.getOrElse(5) { Color(0xFFE040FB) }
        val italicColor = theme.ansi.getOrElse(6) { Color(0xFF00BCD4) }
        val linkColor = theme.uiAccent

        while (i < text.length) {
            when {
                /* Bold: **text** */
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > 0) {
                        withStyle(SpanStyle(color = boldColor, fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i]); i++
                    }
                }
                /* Italic: *text* (avoid conflict with **). */
                text[i] == '*' && !text.startsWith("**", i) -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > 0) {
                        withStyle(SpanStyle(color = italicColor, fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i]); i++
                    }
                }
                /* Inline code: `code` */
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > 0) {
                        withStyle(SpanStyle(color = codeColor, fontFamily = FontFamily.Monospace)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i]); i++
                    }
                }
                /* Link: [text](url) */
                text[i] == '[' -> {
                    val textEnd = text.indexOf(']', i + 1)
                    if (textEnd > 0 && textEnd + 1 < text.length && text[textEnd + 1] == '(') {
                        val urlEnd = text.indexOf(')', textEnd + 2)
                        if (urlEnd > 0) {
                            val linkText = text.substring(i + 1, textEnd)
                            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                                append(linkText)
                            }
                            i = urlEnd + 1
                        } else {
                            append(text[i]); i++
                        }
                    } else {
                        append(text[i]); i++
                    }
                }
                else -> {
                    append(text[i]); i++
                }
            }
        }
    }
}
