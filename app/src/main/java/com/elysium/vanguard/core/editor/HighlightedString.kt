package com.elysium.vanguard.core.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * PHASE 2.7 — Render tokens to a Compose [AnnotatedString].
 *
 * The theme is a fixed mapping from [TokenKind] to colors. We don't expose a theme
 * picker here — the user can already customize the accent colors via TitanColors,
 * and the editor pulls from those at runtime through [buildHighlighted].
 *
 * Why hard-coded colors and not the Titan palette:
 *   - Syntax highlighting reads best with consistent colors across the app's
 *     phosphor neon look. The "neon green keyword" should look the same regardless
 *     of which theme the user picked — that's what makes a language "look like"
 *     Kotlin or Python.
 *   - We still tint the base text with the active theme's body color so the editor
 *     doesn't feel disconnected from the rest of the app.
 */
object HighlightedString {

    /**
     * Build an [AnnotatedString] with each token painted in its theme color.
     *
     * @param text the source text (already loaded from disk)
     * @param tokens the output of [SyntaxHighlighter.tokenize]
     * @param baseColor foreground color for unstyled text (default body color)
     */
    fun build(
        text: String,
        tokens: List<Token>,
        baseColor: Color = Color(0xFFE6ECF3)
    ): AnnotatedString = buildAnnotatedString {
        append(text)  // start with the full text in the base style
        var pos = 0
        for (token in tokens) {
            // If a token's start isn't adjacent to the previous one, leave the gap
            // with the base style (shouldn't happen because tokenize covers all
            // characters, but defensive in case of future partial tokenization).
            if (token.start > pos) {
                addStyle(SpanStyle(color = baseColor), pos, token.start)
            }
            addStyle(SpanStyle(color = token.kind.color(), fontWeight = token.kind.weight(),
                fontStyle = token.kind.style(), textDecoration = token.kind.decoration()),
                token.start, token.end)
            pos = token.end
        }
        // Tail (only needed if last token didn't reach end-of-text).
        if (pos < text.length) {
            addStyle(SpanStyle(color = baseColor), pos, text.length)
        }
    }
}

/**
 * Color for a token kind. Phosphor/neon palette tuned for a dark background.
 * Numbers are picked to keep luminance contrast > 4.5 with the editor's bg #050810.
 */
private fun TokenKind.color(): Color = when (this) {
    TokenKind.KEYWORD -> Color(0xFFFF79C6)        // hot pink — typical "control word" color
    TokenKind.BUILTIN -> Color(0xFF8BE9FD)        // cyan — standard "library" color
    TokenKind.STRING -> Color(0xFFF1FA8C)         // pale yellow — strings are eye-catchy
    TokenKind.NUMBER -> Color(0xFFBD93F9)         // purple — numeric literals
    TokenKind.COMMENT -> Color(0xFF6272A4)        // muted blue-grey — comments recede
    TokenKind.OPERATOR -> Color(0xFFFFB86C)       // warm orange — operators
    TokenKind.PUNCTUATION -> Color(0xFFF8F8F2)    // off-white — punctuation, but less prominent
    TokenKind.IDENTIFIER -> Color(0xFFF8F8F2)     // body
    TokenKind.TYPE -> Color(0xFF50FA7B)           // bright green — type names
    TokenKind.FUNCTION -> Color(0xFF50FA7B)       // same as type — function names read better green
    TokenKind.ANNOTATION -> Color(0xFFFFB86C)     // annotation marker
    TokenKind.HTML_TAG -> Color(0xFFFF79C6)       // pink tags
    TokenKind.HTML_ATTRIBUTE -> Color(0xFF50FA7B) // green attribute names
    TokenKind.HEADING -> Color(0xFFFF79C6)        // bold pink headings
    TokenKind.EMPHASIS -> Color(0xFFF1FA8C)       // emphasis yellow
    TokenKind.CODE_FENCE -> Color(0xFF50FA7B)     // code blocks green
    TokenKind.LINK -> Color(0xFF8BE9FD)           // link cyan
    TokenKind.WHITESPACE -> Color(0xFFF8F8F2)
    TokenKind.UNKNOWN -> Color(0xFFF8F8F2)
}

private fun TokenKind.weight(): FontWeight? = when (this) {
    TokenKind.KEYWORD -> FontWeight.SemiBold
    TokenKind.HEADING -> FontWeight.Bold
    TokenKind.TYPE -> FontWeight.SemiBold
    else -> null
}

private fun TokenKind.style(): FontStyle? = when (this) {
    TokenKind.COMMENT -> FontStyle.Italic
    TokenKind.EMPHASIS -> FontStyle.Italic
    else -> null
}

private fun TokenKind.decoration(): TextDecoration? = when (this) {
    TokenKind.HEADING -> TextDecoration.Underline
    else -> null
}