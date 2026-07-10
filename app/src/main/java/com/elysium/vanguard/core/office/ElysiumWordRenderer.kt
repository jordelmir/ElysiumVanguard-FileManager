package com.elysium.vanguard.core.office

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * PHASE 9.8.2 — Render an Elysium Word document body into a
 * Compose-friendly [AnnotatedString] using the [ElysiumDocument.StyleHints]
 * the document carries. MVP today: markdown-ish line decorations
 * (`# Heading`, `> quote`, ` ``` ... ``` ` code blocks), `**bold**`
 * and `*italic*` inline emphasis.
 *
 * Phase 9.8.2 — first build; intentionally minimal.
 */
object ElysiumWordRenderer {

    /**
     * Convert a Word document's body bytes (UTF-8) into a styled
     * [AnnotatedString].
     */
    fun render(
        body: ByteArray,
        style: ElysiumDocument.StyleHints
    ): AnnotatedString {
        val text = body.toString(Charsets.UTF_8)
        val baseFont = if (style.font == "monospace") FontFamily.Monospace else FontFamily.Default
        val baseSize = style.fontSizePt.sp
        return buildAnnotatedString {
            val lines = text.lines()
            for ((idx, line) in lines.withIndex()) {
                applyLine(this, line, baseFont, baseSize)
                if (idx < lines.size - 1) {
                    append("\n")
                }
            }
        }
    }

    private fun applyLine(
        b: androidx.compose.ui.text.AnnotatedString.Builder,
        line: String,
        baseFont: FontFamily,
        baseSize: TextUnit
    ) {
        when {
            line.startsWith("# ") -> {
                val text = line.removePrefix("# ")
                b.pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = (baseSize.value + 8).sp, fontFamily = baseFont))
                appendMarkdownInline(b, text, baseFont, baseSize)
                b.pop()
            }
            line.startsWith("## ") -> {
                val text = line.removePrefix("## ")
                b.pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = (baseSize.value + 4).sp, fontFamily = baseFont))
                appendMarkdownInline(b, text, baseFont, baseSize)
                b.pop()
            }
            line.startsWith("### ") -> {
                val text = line.removePrefix("### ")
                b.pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = (baseSize.value + 2).sp, fontFamily = baseFont))
                appendMarkdownInline(b, text, baseFont, baseSize)
                b.pop()
            }
            line.startsWith("> ") -> {
                val text = line.removePrefix("> ")
                b.pushStyle(SpanStyle(fontStyle = FontStyle.Italic, fontFamily = baseFont, fontSize = baseSize))
                appendMarkdownInline(b, text, baseFont, baseSize)
                b.pop()
            }
            line.startsWith("```") -> {
                val text = line.removePrefix("```")
                b.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = baseSize))
                appendMarkdownInline(b, text, FontFamily.Monospace, baseSize)
                b.pop()
            }
            else -> appendMarkdownInline(b, line, baseFont, baseSize)
        }
    }

    /**
     * Append [text] to [b], honoring inline `**bold**` and `*italic*`
     * markers. Outside of those, the text is appended with the base style.
     */
    private fun appendMarkdownInline(
        b: androidx.compose.ui.text.AnnotatedString.Builder,
        text: String,
        baseFont: FontFamily,
        baseSize: TextUnit
    ) {
        var i = 0
        // Greedy: process markers left to right.
        while (i < text.length) {
            val boldIdx = if (i + 1 < text.length && text.startsWith("**", i)) {
                val close = text.indexOf("**", i + 2)
                if (close > i + 2) close else -1
            } else -1
            val italicIdx = if (text[i] == '*') {
                val close = text.indexOf("*", i + 1)
                if (close > i + 1) close else -1
            } else -1

            when {
                boldIdx > 0 && (italicIdx < 0 || boldIdx <= italicIdx) -> {
                    b.pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontFamily = baseFont, fontSize = baseSize))
                    b.append(text.substring(i + 2, boldIdx))
                    b.pop()
                    i = boldIdx + 2
                }
                italicIdx > 0 -> {
                    b.pushStyle(SpanStyle(fontStyle = FontStyle.Italic, fontFamily = baseFont, fontSize = baseSize))
                    b.append(text.substring(i + 1, italicIdx))
                    b.pop()
                    i = italicIdx + 1
                }
                else -> {
                    b.append(text[i])
                    i += 1
                }
            }
        }
    }
}
