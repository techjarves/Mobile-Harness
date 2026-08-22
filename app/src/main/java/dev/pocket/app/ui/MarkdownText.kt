package dev.pocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
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
import dev.pocket.app.ui.theme.PocketOrange
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface MarkdownBlock {
    data class Header(val level: Int, val text: String) : MarkdownBlock
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock
    data class BulletItem(val depth: Int, val text: String) : MarkdownBlock
    data class NumberedItem(val number: String, val text: String) : MarkdownBlock
    data class BlockQuote(val text: String) : MarkdownBlock
    object HorizontalRule : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    onRunCode: ((String) -> Unit)? = null,
) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Header -> HeaderBlock(block)
                is MarkdownBlock.CodeBlock -> CodeSnippetBlock(block, onRunCode)
                is MarkdownBlock.BulletItem -> BulletBlock(block, color)
                is MarkdownBlock.NumberedItem -> NumberedBlock(block, color)
                is MarkdownBlock.BlockQuote -> QuoteBlock(block)
                is MarkdownBlock.HorizontalRule -> HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = formatInlineMarkdown(block.text),
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                        color = color,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderBlock(header: MarkdownBlock.Header) {
    val style = when (header.level) {
        1 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp)
        2 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp)
        else -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
    Text(
        text = formatInlineMarkdown(header.text),
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun BulletBlock(item: MarkdownBlock.BulletItem, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (item.depth * 14).dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp, end = 8.dp)
                .size(5.dp)
                .background(PocketOrange, CircleShape),
        )
        Text(
            text = formatInlineMarkdown(item.text),
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            color = color,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun NumberedBlock(item: MarkdownBlock.NumberedItem, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = item.number,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = PocketOrange),
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            text = formatInlineMarkdown(item.text),
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            color = color,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QuoteBlock(quote: MarkdownBlock.BlockQuote) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(24.dp)
                .background(PocketOrange, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = formatInlineMarkdown(quote.text),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}

@Composable
private fun CodeSnippetBlock(block: MarkdownBlock.CodeBlock, onRunCode: ((String) -> Unit)?) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF14171E),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF2A2E39), RoundedCornerShape(12.dp)),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1C202B))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = block.language.ifBlank { "code" },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF9AA0A6),
                    fontFamily = FontFamily.Monospace,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val shellLanguage = block.language.lowercase() in setOf("", "bash", "sh", "shell", "zsh", "console", "terminal")
                    if (onRunCode != null && shellLanguage) {
                        IconButton(
                            onClick = { onRunCode(block.code.trim()) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Run in project terminal",
                                tint = PocketOrange,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(block.code))
                            copied = true
                            scope.launch {
                                delay(2000)
                                copied = false
                            }
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = "Copy code",
                            tint = if (copied) PocketOrange else Color(0xFF9AA0A6),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Text(
                    text = block.code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Color(0xFFE2E8F0),
                )
            }
        }
    }
}

@Composable
private fun formatInlineMarkdown(text: String): AnnotatedString {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val codeColor = PocketOrange
    val primaryColor = MaterialTheme.colorScheme.primary

    return remember(text, codeBg, codeColor, primaryColor) {
        buildAnnotatedString {
            var i = 0
            val len = text.length

            while (i < len) {
                when {
                    // Inline Code: `code`
                    text[i] == '`' -> {
                        val end = text.indexOf('`', i + 1)
                        if (end != -1) {
                            withStyle(
                                SpanStyle(
                                    fontFamily = FontFamily.Monospace,
                                    background = codeBg,
                                    color = codeColor,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            ) {
                                append(" ${text.substring(i + 1, end)} ")
                            }
                            i = end + 1
                        } else {
                            append(text[i])
                            i++
                        }
                    }
                    // Bold & Italic: ***text***
                    text.startsWith("***", i) -> {
                        val end = text.indexOf("***", i + 3)
                        if (end != -1) {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                                append(text.substring(i + 3, end))
                            }
                            i = end + 3
                        } else {
                            append(text[i])
                            i++
                        }
                    }
                    // Bold: **text** or __text__
                    text.startsWith("**", i) -> {
                        val end = text.indexOf("**", i + 2)
                        if (end != -1) {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                append(text.substring(i + 2, end))
                            }
                            i = end + 2
                        } else {
                            append(text[i])
                            i++
                        }
                    }
                    text.startsWith("__", i) -> {
                        val end = text.indexOf("__", i + 2)
                        if (end != -1) {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                append(text.substring(i + 2, end))
                            }
                            i = end + 2
                        } else {
                            append(text[i])
                            i++
                        }
                    }
                    // Italic: *text* (avoiding single bullet at start)
                    text[i] == '*' -> {
                        val end = text.indexOf('*', i + 1)
                        if (end != -1 && end > i + 1) {
                            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                                append(text.substring(i + 1, end))
                            }
                            i = end + 1
                        } else {
                            append(text[i])
                            i++
                        }
                    }
                    // Strikethrough: ~~text~~
                    text.startsWith("~~", i) -> {
                        val end = text.indexOf("~~", i + 2)
                        if (end != -1) {
                            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                                append(text.substring(i + 2, end))
                            }
                            i = end + 2
                        } else {
                            append(text[i])
                            i++
                        }
                    }
                    // Link: [label](url)
                    text[i] == '[' -> {
                        val closeBracket = text.indexOf(']', i + 1)
                        val openParen = if (closeBracket != -1) text.indexOf('(', closeBracket) else -1
                        val closeParen = if (openParen == closeBracket + 1) text.indexOf(')', openParen) else -1

                        if (closeBracket != -1 && openParen == closeBracket + 1 && closeParen != -1) {
                            val label = text.substring(i + 1, closeBracket)
                            withStyle(SpanStyle(color = primaryColor, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium)) {
                                append(label)
                            }
                            i = closeParen + 1
                        } else {
                            append(text[i])
                            i++
                        }
                    }
                    else -> {
                        append(text[i])
                        i++
                    }
                }
            }
        }
    }
}

private fun parseMarkdown(raw: String): List<MarkdownBlock> {
    val lines = raw.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var inCodeBlock = false
    var codeLang = ""
    val codeLines = mutableListOf<String>()
    val currentParagraphLines = mutableListOf<String>()

    fun flushParagraph() {
        if (currentParagraphLines.isNotEmpty()) {
            val text = currentParagraphLines.joinToString("\n").trim()
            if (text.isNotEmpty()) {
                blocks.add(MarkdownBlock.Paragraph(text))
            }
            currentParagraphLines.clear()
        }
    }

    for (line in lines) {
        val trimmed = line.trim()

        if (trimmed.startsWith("```")) {
            if (inCodeBlock) {
                blocks.add(MarkdownBlock.CodeBlock(codeLang, codeLines.joinToString("\n")))
                codeLines.clear()
                codeLang = ""
                inCodeBlock = false
            } else {
                flushParagraph()
                inCodeBlock = true
                codeLang = trimmed.removePrefix("```").trim()
            }
            continue
        }

        if (inCodeBlock) {
            codeLines.add(line)
            continue
        }

        if (trimmed.isEmpty()) {
            flushParagraph()
            continue
        }

        // Horizontal Rule
        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            flushParagraph()
            blocks.add(MarkdownBlock.HorizontalRule)
            continue
        }

        // Headings (#, ##, ###)
        if (trimmed.startsWith("#")) {
            flushParagraph()
            val level = trimmed.takeWhile { it == '#' }.length
            val text = trimmed.drop(level).trim()
            blocks.add(MarkdownBlock.Header(level, text))
            continue
        }

        // Blockquotes (> text)
        if (trimmed.startsWith(">")) {
            flushParagraph()
            val text = trimmed.removePrefix(">").trim()
            blocks.add(MarkdownBlock.BlockQuote(text))
            continue
        }

        // Unordered List (- item, * item, + item)
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
            flushParagraph()
            val indent = line.takeWhile { it.isWhitespace() }.length / 2
            val text = trimmed.substring(2).trim()
            blocks.add(MarkdownBlock.BulletItem(indent, text))
            continue
        }

        // Ordered List (1. item, 2. item)
        val orderedMatch = Regex("^([0-9]+[.)])\\s+(.*)").find(trimmed)
        if (orderedMatch != null) {
            flushParagraph()
            val num = orderedMatch.groupValues[1]
            val text = orderedMatch.groupValues[2]
            blocks.add(MarkdownBlock.NumberedItem(num, text))
            continue
        }

        // Normal paragraph text
        currentParagraphLines.add(line)
    }

    if (inCodeBlock) {
        blocks.add(MarkdownBlock.CodeBlock(codeLang, codeLines.joinToString("\n")))
    }
    flushParagraph()

    return blocks
}
