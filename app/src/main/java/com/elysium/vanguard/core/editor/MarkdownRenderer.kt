package com.elysium.vanguard.core.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * PHASE 2.6 — Markdown renderer.
 *
 * Scope: a pragmatic subset of CommonMark — headings, paragraphs, emphasis,
 * inline code, code fences, links, lists (ordered + unordered), blockquotes.
 * No tables, no images, no HTML pass-through. We render to an [AnnotatedString]
 * so the editor can show the preview in the same view tree as the source.
 *
 * Why not use a library: the popular options (Markwon, commonmark-java) are
 * 200–400 KB and either pull Android-specific WebView deps or are too
 * general-purpose. Our subset is ~250 lines and produces a single
 * AnnotatedString, which is what Compose wants anyway.
 *
 * How to use:
 *   val rendered = MarkdownRenderer().render("# Hello\n\nbody")
 *   Text(rendered, style = TextStyle(color = Color.White, fontSize = 14.sp))
 */
class MarkdownRenderer(
    private val baseColor: Color = Color(0xFFE6ECF3),
    private val baseFontSize: TextUnit = 14.sp
) {

    fun render(markdown: String): AnnotatedString = buildAnnotatedString {
        val lines = markdown.split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trim().startsWith("```") -> {
                    val lang = line.trim().removePrefix("```").trim()
                    val fenceStart = i
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) i++
                    appendCodeFence(this, lines, fenceStart, i.coerceAtMost(lines.size), lang)
                    if (i < lines.size) i++
                }
                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length.coerceAtMost(6)
                    val text = line.substring(level).trim()
                    appendHeading(this, text, level)
                    append('\n')
                    i++
                }
                i + 1 < lines.size && lines[i + 1].matches(Regex("=+")) && line.isNotBlank() -> {
                    appendHeading(this, line, 1)
                    append('\n')
                    i += 2
                }
                i + 1 < lines.size && lines[i + 1].matches(Regex("-+")) && line.isNotBlank() -> {
                    appendHeading(this, line, 2)
                    append('\n')
                    i += 2
                }
                line.startsWith(">") -> {
                    val collected = StringBuilder()
                    while (i < lines.size && lines[i].startsWith(">")) {
                        collected.append(lines[i].removePrefix(">").trim()).append(' ')
                        i++
                    }
                    appendBlockquote(this, collected.toString().trim())
                    append('\n')
                }
                UNORDERED_REGEX.matches(line) -> {
                    i = appendList(this, lines, i, ordered = false)
                }
                ORDERED_REGEX.matches(line) -> {
                    i = appendList(this, lines, i, ordered = true)
                }
                line.isBlank() -> {
                    append('\n')
                    i++
                }
                else -> {
                    val paragraph = StringBuilder()
                    while (i < lines.size && lines[i].isNotBlank() &&
                        !lines[i].startsWith("#") &&
                        !lines[i].startsWith(">") &&
                        !UNORDERED_REGEX.matches(lines[i]) &&
                        !ORDERED_REGEX.matches(lines[i]) &&
                        !lines[i].trim().startsWith("```")
                    ) {
                        if (paragraph.isNotEmpty()) paragraph.append(' ')
                        paragraph.append(lines[i])
                        i++
                    }
                    appendParagraph(this, paragraph.toString())
                    append('\n')
                }
            }
        }
    }

    private fun appendHeading(target: AnnotatedString.Builder, text: String, level: Int) {
        val size = when (level) {
            1 -> (baseFontSize.value * 1.8f).sp
            2 -> (baseFontSize.value * 1.5f).sp
            3 -> (baseFontSize.value * 1.3f).sp
            4 -> (baseFontSize.value * 1.15f).sp
            5 -> (baseFontSize.value * 1.05f).sp
            else -> baseFontSize
        }
        val start = target.length
        target.append(text)
        target.addStyle(
            SpanStyle(
                color = Color(0xFFFF79C6),
                fontSize = size,
                fontWeight = FontWeight.Bold
            ),
            start, target.length
        )
    }

    private fun appendParagraph(target: AnnotatedString.Builder, text: String) {
        val start = target.length
        target.append(text)
        target.addStyle(
            SpanStyle(color = baseColor, fontSize = baseFontSize),
            start, target.length
        )
        appendInlineMarkdown(target, start, text)
    }

    private fun appendBlockquote(target: AnnotatedString.Builder, text: String) {
        val start = target.length
        target.append("│ ")
        target.append(text)
        target.addStyle(
            SpanStyle(
                color = Color(0xFF6272A4),
                fontSize = baseFontSize,
                fontStyle = FontStyle.Italic
            ),
            start, target.length
        )
    }

    private fun appendCodeFence(
        target: AnnotatedString.Builder,
        lines: List<String>,
        from: Int,
        to: Int,
        @Suppress("UNUSED_PARAMETER") lang: String
    ) {
        val start = target.length
        val joined = lines.subList(from + 1, to).joinToString("\n")
        target.append(joined)
        target.addStyle(
            SpanStyle(
                color = Color(0xFF50FA7B),
                fontFamily = FontFamily.Monospace,
                background = Color(0xFF1A2030),
                fontSize = baseFontSize
            ),
            start, target.length
        )
        target.append('\n')
    }

    private fun appendList(target: AnnotatedString.Builder, lines: List<String>, startIdx: Int, ordered: Boolean): Int {
        var i = startIdx
        var counter = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                ordered && ORDERED_REGEX.matches(line) -> {
                    counter++
                    val content = ORDERED_CONTENT.find(line)?.groupValues?.get(1) ?: line
                    val start = target.length
                    target.append("  ${counter}. $content")
                    target.addStyle(
                        SpanStyle(color = baseColor, fontSize = baseFontSize),
                        start, target.length
                    )
                    appendInlineMarkdown(target, start, content)
                    target.append('\n')
                    i++
                }
                !ordered && UNORDERED_REGEX.matches(line) -> {
                    val content = UNORDERED_CONTENT.find(line)?.groupValues?.get(1) ?: line
                    val start = target.length
                    target.append("  • $content")
                    target.addStyle(
                        SpanStyle(color = baseColor, fontSize = baseFontSize),
                        start, target.length
                    )
                    appendInlineMarkdown(target, start, content)
                    target.append('\n')
                    i++
                }
                line.isBlank() -> {
                    i++
                }
                else -> {
                    val start = target.length
                    target.append("    ${line.trim()}")
                    target.addStyle(
                        SpanStyle(color = baseColor, fontSize = baseFontSize),
                        start, target.length
                    )
                    target.append('\n')
                    i++
                }
            }
        }
        return i
    }

    /**
     * Walk an inline string and apply SpanStyles for `*italic*`, `**bold**`,
     * `***both***`, backtick-code, and [text](url). The text was already
     * appended to the builder; we only paint styles.
     */
    private fun appendInlineMarkdown(
        target: AnnotatedString.Builder,
        baseOffset: Int,
        text: String
    ) {
        val len = text.length
        var i = 0
        while (i < len) {
            val done = when {
                i + 5 <= len && text.startsWith("***", i) -> {
                    val end = text.indexOf("***", i + 3)
                    if (end > i + 3) {
                        target.addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic),
                            baseOffset + i + 3, baseOffset + end)
                        end + 3
                    } else null
                }
                i + 4 <= len && text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) {
                        target.addStyle(SpanStyle(fontWeight = FontWeight.Bold),
                            baseOffset + i + 2, baseOffset + end)
                        end + 2
                    } else null
                }
                i + 2 <= len && text[i] == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i + 1) {
                        target.addStyle(SpanStyle(fontStyle = FontStyle.Italic),
                            baseOffset + i + 1, baseOffset + end)
                        end + 1
                    } else null
                }
                i + 2 <= len && text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) {
                        target.addStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF50FA7B),
                                background = Color(0xFF1A2030)
                            ),
                            baseOffset + i + 1, baseOffset + end
                        )
                        end + 1
                    } else null
                }
                text.startsWith("[", i) -> {
                    val closeBracket = text.indexOf(']', i + 1)
                    if (closeBracket > i && closeBracket + 1 < len && text[closeBracket + 1] == '(') {
                        val closeParen = text.indexOf(')', closeBracket + 2)
                        if (closeParen > closeBracket) {
                            target.addStyle(
                                SpanStyle(color = Color(0xFF8BE9FD), textDecoration = TextDecoration.Underline),
                                baseOffset + i, baseOffset + closeParen + 1
                            )
                            closeParen + 1
                        } else null
                    } else null
                }
                else -> null
            }
            i = done ?: (i + 1)
        }
    }

    companion object {
        private val UNORDERED_REGEX = Regex("""^\s*[-*+]\s+(.*)$""")
        private val ORDERED_REGEX = Regex("""^\s*\d+\.\s+(.*)$""")
        private val UNORDERED_CONTENT = Regex("""^\s*[-*+]\s+(.*)$""")
        private val ORDERED_CONTENT = Regex("""^\s*\d+\.\s+(.*)$""")
    }
}