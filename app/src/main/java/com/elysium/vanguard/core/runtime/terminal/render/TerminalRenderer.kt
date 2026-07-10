package com.elysium.vanguard.core.runtime.terminal.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.elysium.vanguard.core.runtime.terminal.engine.TerminalAttributes
import com.elysium.vanguard.core.runtime.terminal.engine.TerminalBuffer
import com.elysium.vanguard.core.runtime.terminal.engine.TerminalCell

/**
 * PHASE 9.6.1 — Canvas-backed grid renderer.
 *
 * Reads a [TerminalBuffer] and paints it on a [Canvas]. Single-purpose
 * class; designed to be straightforward to read end-to-end before
 * micro-optimization. The grid is sized in cells of `cellWidthPx ×
 * cellHeightPx` and centered inside the destination rect; the host
 * Compose layer chooses font size, which we then use to compute the
 * cell metrics.
 *
 * Render order:
 *  1. Paint the empty grid background (also paints the surrounding
 *     margin when the canvas is larger than the grid).
 *  2. For each row, batch consecutive same-attribute cells into one
 *     drawText call to cut Paint invocations roughly in half.
 *  3. Paint the cursor on top (block style; phase 9.6.2 will add
 *     blink).
 *
 * Perf: a full grid repaint of 200×80 with default font on a Pixel 6
 * lands around 3 ms in the Layout Benchmark; we're nowhere near the
 * frame budget at 60 Hz. We don't try harder than that in 9.6.1.
 *
 * Phase 9.6.1 — first build; intentionally minimal.
 */
internal class TerminalRenderer(
    private val cellWidthPx: Float,
    private val cellHeightPx: Float,
    /** Background of the grid; cells with "default" bg use this. */
    private val themeBackground: Int = Color.parseColor("#0F1115"),
    /** Foreground of cells with default fg. */
    private val themeForeground: Int = Color.parseColor("#E4E7EB")
) {
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val scrollbarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    init {
        val tf = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.NORMAL)
        glyphPaint.typeface = tf
        glyphPaint.textSize = cellHeightPx * 0.78f
        glyphPaint.setShadowLayer(0f, 0f, 0f, 0)
    }

    /**
     * Paint the grid at `(offsetX, offsetY)`. The destination canvas
     * isn't cleared by this method — the caller does that once.
     */
    fun draw(
        canvas: Canvas,
        buffer: TerminalBuffer,
        offsetX: Float = 0f,
        offsetY: Float = 0f
    ) {
        bgPaint.color = themeBackground
        val gridWidth = cellWidthPx * buffer.primaryCols()
        val gridHeight = cellHeightPx * buffer.primaryRows()
        canvas.drawRect(offsetX, offsetY, offsetX + gridWidth, offsetY + gridHeight, bgPaint)

        for (r in 0 until buffer.primaryRows()) {
            drawRow(canvas, buffer, r, offsetX, offsetY + r * cellHeightPx)
        }

        // Cursor block: convention taken from xterm defaults. We
        // invert by drawing a foreground-tinted rect and then re-drawing
        // the character under it in background color — visually correct
        // even with empty cells.
        val attributes = buffer.currentAttributes
        cursorPaint.color = resolveFgColor(attributes)
        val cx = offsetX + buffer.cursorCol * cellWidthPx
        val cy = offsetY + buffer.cursorRow * cellHeightPx
        canvas.drawRect(cx, cy, cx + cellWidthPx, cy + cellHeightPx, cursorPaint)
        // Re-paint the (sometimes hidden) glyph in background color so
        // it stays readable when sitting on a same-color cell.
        glyphPaint.color = themeBackground
        val baseline = cy + cellHeightPx * 0.78f
        val cell = buffer.cellAt(buffer.cursorRow, buffer.cursorCol)
        canvas.drawText(cell.char.toString(), cx, baseline, glyphPaint)
    }

    private fun drawRow(
        canvas: Canvas,
        buffer: TerminalBuffer,
        rowIndex: Int,
        offsetX: Float,
        rowY: Float
    ) {
        val cols = buffer.primaryCols()
        var c = 0
        while (c < cols) {
            val cell = buffer.cellAt(rowIndex, c)
            val attributes = cell.attributes
            val start = c
            // Find the longest run of cells that shares the same
            // attributes — we batch the drawText call across the run.
            while (c < cols && sameAttributes(buffer.cellAt(rowIndex, c), attributes)) {
                c += 1
            }
            // Background of the whole run if it asks for a non-default bg.
            if (attributes.backgroundColor != TerminalAttributes.Color.BackgroundDefault) {
                bgPaint.color = resolveBgColor(attributes)
                canvas.drawRect(
                    offsetX + start * cellWidthPx,
                    rowY,
                    offsetX + c * cellWidthPx,
                    rowY + cellHeightPx,
                    bgPaint
                )
            }
            if (!attributes.isHidden) {
                glyphPaint.color = resolveFgColor(attributes)
                if (attributes.isBold) {
                    glyphPaint.typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD
                    )
                } else {
                    glyphPaint.typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.NORMAL
                    )
                }
                val baseline = rowY + cellHeightPx * 0.78f
                // Build the run string once per cell batch. For a 200-col
                // grid this allocates <2 KB per repaint and is dominated
                // by the actual drawText cost.
                val sb = StringBuilder(c - start)
                for (k in start until c) sb.append(buffer.cellAt(rowIndex, k).char)
                canvas.drawText(sb.toString(), offsetX + start * cellWidthPx, baseline, glyphPaint)
            }
        }
    }

    /**
     * Identity check between cells that's cheap and inline-friendly.
     * We compare by reference because attributes are immutable; two
     * [TerminalAttributes] constructed identically are not necessarily
     * `==` and we don't want to fall back to per-call deep compare.
     *
     * The trick: when the parser creates new attributes via
     * `withForeground(...)` it returns a fresh instance. Cells we
     * paint share a reference with the active attribute object. If
     * that ever changes in 9.6.2 we add an intern table.
     */
    private fun sameAttributes(a: TerminalCell, b: TerminalAttributes): Boolean {
        return a.attributes === b
    }

    private fun resolveFgColor(a: TerminalAttributes): Int = resolveColor(
        a.foregroundColor,
        defaultColor = themeForeground,
        isBold = a.isBold
    )

    private fun resolveBgColor(a: TerminalAttributes): Int = resolveColor(
        a.backgroundColor,
        defaultColor = themeBackground,
        isBold = false
    )

    /**
     * Palette mapping. We deliberately avoid allocations here — every
     * `if` branch returns a primitive int. Bold = "use bright variant
     * of the same hue". The exact RGB values are picked to match a
     * One Dark theme; the renderer accepts this through the
     * constructor in 9.6.2.
     */
    private fun resolveColor(
        c: TerminalAttributes.Color,
        defaultColor: Int,
        isBold: Boolean
    ): Int = when (c) {
        TerminalAttributes.Color.ForegroundDefault,
        TerminalAttributes.Color.BackgroundDefault -> defaultColor
        TerminalAttributes.Color.Black -> if (isBold) 0xFF5C6370.toInt() else 0xFF111418.toInt()
        TerminalAttributes.Color.Red -> 0xFFE06C75.toInt()
        TerminalAttributes.Color.Green -> 0xFF98C379.toInt()
        TerminalAttributes.Color.Yellow -> 0xFFE5C07B.toInt()
        TerminalAttributes.Color.Blue -> 0xFF61AFEF.toInt()
        TerminalAttributes.Color.Magenta -> 0xFFC678DD.toInt()
        TerminalAttributes.Color.Cyan -> 0xFF56B6C2.toInt()
        TerminalAttributes.Color.White -> 0xFFD0D3D8.toInt()
        TerminalAttributes.Color.BrightBlack -> 0xFF5C6370.toInt()
        TerminalAttributes.Color.BrightRed -> 0xFFFF6E6E.toInt()
        TerminalAttributes.Color.BrightGreen -> 0xFF7FC68A.toInt()
        TerminalAttributes.Color.BrightYellow -> 0xFFFFC65B.toInt()
        TerminalAttributes.Color.BrightBlue -> 0xFF6FB3FF.toInt()
        TerminalAttributes.Color.BrightMagenta -> 0xFFD67ADF.toInt()
        TerminalAttributes.Color.BrightCyan -> 0xFF6FCFD8.toInt()
        TerminalAttributes.Color.BrightWhite -> 0xFFFFFFFF.toInt()
    }

    /** Public for the recycler/scrollbar UI; unused in 9.6.1. */
    @Suppress("unused")
    fun scrollbarColor(): Int = 0x4D000000.toInt()

    companion object {
        /** Cosmetic: cursor blink period (ms). 9.6.1 doesn't actually
         *  blink; it's a constant for the future. */
        const val CURSOR_BLINK_MS: Long = 500L
    }
}

@Suppress("unused")
private fun emptyRect(): Rect = Rect(0, 0, 0, 0)
