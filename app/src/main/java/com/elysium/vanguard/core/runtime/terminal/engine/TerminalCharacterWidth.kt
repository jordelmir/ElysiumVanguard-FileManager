package com.elysium.vanguard.core.runtime.terminal.engine

/**
 * Cell-width classification for the subset of Unicode a terminal grid needs.
 *
 * Android's text shaper knows how to draw Unicode, but it does not decide how
 * many terminal columns a glyph owns. This deliberately follows the stable
 * wcwidth-style East Asian wide ranges and treats combining marks, variation
 * selectors, ZWJ, and emoji modifiers as zero-width additions to the prior
 * cell cluster. Ambiguous-width characters remain one column, matching the
 * default xterm/UTF-8 expectation.
 */
internal object TerminalCharacterWidth {
    const val ZERO = 0
    const val SINGLE = 1
    const val DOUBLE = 2

    fun columns(codePoint: Int): Int = when {
        codePoint == 0 -> ZERO
        isZeroWidth(codePoint) -> ZERO
        isWide(codePoint) -> DOUBLE
        else -> SINGLE
    }

    fun isZeroWidth(codePoint: Int): Boolean {
        if (codePoint == ZERO_WIDTH_JOINER || codePoint in VARIATION_SELECTOR_START..VARIATION_SELECTOR_END ||
            codePoint in SUPPLEMENTARY_VARIATION_SELECTOR_START..SUPPLEMENTARY_VARIATION_SELECTOR_END ||
            codePoint in EMOJI_MODIFIER_START..EMOJI_MODIFIER_END
        ) return true
        return when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> true
            else -> false
        }
    }

    private fun isWide(codePoint: Int): Boolean =
        codePoint in 0x1100..0x115F ||
            codePoint in 0x2329..0x232A ||
            codePoint in 0x2E80..0xA4CF ||
            codePoint in 0xAC00..0xD7A3 ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0xFE10..0xFE19 ||
            codePoint in 0xFE30..0xFE6F ||
            codePoint in 0xFF00..0xFF60 ||
            codePoint in 0xFFE0..0xFFE6 ||
            codePoint in 0x1F300..0x1FAFF ||
            codePoint in 0x20000..0x3FFFD

    private const val ZERO_WIDTH_JOINER = 0x200D
    private const val VARIATION_SELECTOR_START = 0xFE00
    private const val VARIATION_SELECTOR_END = 0xFE0F
    private const val SUPPLEMENTARY_VARIATION_SELECTOR_START = 0xE0100
    private const val SUPPLEMENTARY_VARIATION_SELECTOR_END = 0xE01EF
    private const val EMOJI_MODIFIER_START = 0x1F3FB
    private const val EMOJI_MODIFIER_END = 0x1F3FF
}
