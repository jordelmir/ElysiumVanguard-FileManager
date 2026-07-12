package com.elysium.vanguard.core.runtime.terminal.engine

/**
 * PHASE 9.6.1 — Terminal attribute state for a single cell.
 *
 * Models the SGR (Select Graphic Rendition) subset from VT100 / ECMA-48
 * that 99 % of command-line tools emit. We track foreground color,
 * background color, and a small bitfield of flags (bold, dim, italic,
 * underline, reverse, blink, hidden). Anything more exotic (24-bit true
 * color, font families, blinking) is intentionally out of scope for the
 * first build — they can come in a follow-up without breaking the
 * existing buffer layout.
 *
 * Memory: one Int = packed cell attribute + one CharArray entry. ~24 bytes
 * per cell on a 16-bit JVM. A 200×80 grid with 1000 lines of scrollback
 * fits comfortably under 5 MB.
 *
 * Phase 9.6.1 — first build; intentionally minimal.
 */
internal data class TerminalAttributes(
    val foregroundColor: Color,
    val backgroundColor: Color,
    val flags: Int,
    /** Packed opaque RGB override from SGR 38/48; null means ANSI/default. */
    val foregroundRgb: Int? = null,
    val backgroundRgb: Int? = null
) {
    fun withForeground(color: Color): TerminalAttributes =
        if (color === foregroundColor && foregroundRgb == null) this
        else copy(foregroundColor = color, foregroundRgb = null)

    fun withBackground(color: Color): TerminalAttributes =
        if (color === backgroundColor && backgroundRgb == null) this
        else copy(backgroundColor = color, backgroundRgb = null)

    fun withForegroundRgb(rgb: Int): TerminalAttributes =
        copy(foregroundColor = Color.ForegroundDefault, foregroundRgb = rgb and 0x00FFFFFF)

    fun withBackgroundRgb(rgb: Int): TerminalAttributes =
        copy(backgroundColor = Color.BackgroundDefault, backgroundRgb = rgb and 0x00FFFFFF)

    fun withFlag(flag: Flag, on: Boolean): TerminalAttributes {
        val newFlags = if (on) flags or flag.mask else flags and flag.mask.inv()
        return if (newFlags == flags) this else copy(flags = newFlags)
    }

    /**
     * Render swap (reverse video / "invert"). Inverse of the canonical
     * SGR 7 / 27; we apply it at draw time by swapping the two colors
     * rather than flipping a flag, since the renderer needs the actual
     * colors in hand.
     */
    fun inverted(): TerminalAttributes =
        copy(
            foregroundColor = backgroundColor,
            backgroundColor = foregroundColor,
            foregroundRgb = backgroundRgb,
            backgroundRgb = foregroundRgb
        )

    val isBold: Boolean get() = flags and Flag.BOLD.mask != 0
    val isUnderline: Boolean get() = flags and Flag.UNDERLINE.mask != 0
    val isInverse: Boolean get() = flags and Flag.INVERSE.mask != 0
    val isHidden: Boolean get() = flags and Flag.HIDDEN.mask != 0

    companion object {
        val DEFAULT = TerminalAttributes(
            foregroundColor = Color.ForegroundDefault,
            backgroundColor = Color.BackgroundDefault,
            flags = 0,
            foregroundRgb = null,
            backgroundRgb = null
        )
    }

    /**
     * Color palette aligned with the standard 16 ANSI colors, plus the
     * "default" sentinel that maps to the theme at draw time. We don't
     * pre-resolve "default" because the renderer reads the live theme.
     */
    enum class Color {
        ForegroundDefault, BackgroundDefault,

        Black, Red, Green, Yellow, Blue, Magenta, Cyan, White,

        BrightBlack, BrightRed, BrightGreen, BrightYellow,
        BrightBlue, BrightMagenta, BrightCyan, BrightWhite;

        /** Eight-bit palette lookup helper (SGR 38;5;n). */
        companion object {
            fun fromAnsiIndex(index: Int): Color? = when (index) {
                0 -> Black
                1 -> Red
                2 -> Green
                3 -> Yellow
                4 -> Blue
                5 -> Magenta
                6 -> Cyan
                7 -> White
                8 -> BrightBlack
                9 -> BrightRed
                10 -> BrightGreen
                11 -> BrightYellow
                12 -> BrightBlue
                13 -> BrightMagenta
                14 -> BrightCyan
                15 -> BrightWhite
                else -> null
            }
        }
    }

    /**
     * Bitfield flag definitions. Mask is `1 shl bit`; `bit` doubles as the
     * canonical SGR parameter index (so SGR 4 → underline via Flag 4.
     * UNDERLINE). Keeping them in the same enum keeps parser ↔ state in
     * lockstep.
     */
    enum class Flag(val bit: Int) {
        BOLD(0),
        DIM(1),
        ITALIC(2),
        UNDERLINE(3),
        BLINK(4),
        INVERSE(6),
        HIDDEN(7);

        val mask: Int get() = 1 shl bit
    }
}
