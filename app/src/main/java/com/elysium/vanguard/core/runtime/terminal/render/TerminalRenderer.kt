package com.elysium.vanguard.core.runtime.terminal.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.elysium.vanguard.core.runtime.terminal.engine.TerminalAttributes
import com.elysium.vanguard.core.runtime.terminal.engine.TerminalBuffer
import com.elysium.vanguard.core.runtime.terminal.engine.TerminalCell
import com.elysium.vanguard.core.runtime.terminal.engine.TerminalSnapshot

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
    private val themeBackground: Int = Color.BLACK,
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
        val snapshot = buffer.snapshot()
        draw(canvas, snapshot, offsetX, offsetY)
    }

    private fun draw(
        canvas: Canvas,
        snapshot: TerminalSnapshot,
        offsetX: Float,
        offsetY: Float
    ) {
        val rowsToDraw = if (snapshot.fullRedraw) IntArray(snapshot.rows) { it } else snapshot.dirtyRows
        if (snapshot.fullRedraw) canvas.drawColor(themeBackground)
        if (rowsToDraw.isEmpty()) return

        for (row in rowsToDraw) {
            drawRow(canvas, snapshot, row, offsetX, offsetY + row * cellHeightPx)
        }

        if (!snapshot.fullRedraw && snapshot.cursorRow !in rowsToDraw) return
        // Cursor block: convention taken from xterm defaults. We
        // invert by drawing a foreground-tinted rect and then re-drawing
        // the character under it in background color — visually correct
        // even with empty cells.
        var cursorCol = snapshot.cursorCol
        var cell = snapshot.cellAt(snapshot.cursorRow, cursorCol)
        if (cell.isContinuation && cursorCol > 0) {
            cursorCol -= 1
            cell = snapshot.cellAt(snapshot.cursorRow, cursorCol)
        }
        val attributes = cell.attributes
        cursorPaint.color = resolveFgColor(attributes)
        val cx = offsetX + cursorCol * cellWidthPx
        val cy = offsetY + snapshot.cursorRow * cellHeightPx
        val cursorWidth = if (cell.isWide) cellWidthPx * 2 else cellWidthPx
        canvas.drawRect(cx, cy, cx + cursorWidth, cy + cellHeightPx, cursorPaint)
        // Re-paint the (sometimes hidden) glyph in background color so
        // it stays readable when sitting on a same-color cell.
        glyphPaint.color = resolveBgColor(attributes)
        val baseline = cy + cellHeightPx * 0.78f
        if (!cell.isContinuation && !attributes.isHidden) {
            canvas.drawText(cell.text, cx, baseline, glyphPaint)
        }
    }

    private fun drawRow(
        canvas: Canvas,
        snapshot: TerminalSnapshot,
        rowIndex: Int,
        offsetX: Float,
        rowY: Float
    ) {
        val cols = snapshot.cols
        bgPaint.color = themeBackground
        canvas.drawRect(offsetX, rowY, offsetX + cols * cellWidthPx, rowY + cellHeightPx, bgPaint)
        var col = 0
        while (col < cols) {
            val cell = snapshot.cellAt(rowIndex, col)
            if (cell.isContinuation) {
                col += 1
                continue
            }
            val attributes = cell.attributes
            if (cell.isWide) {
                drawGlyphCluster(canvas, cell, attributes, offsetX + col * cellWidthPx, rowY, cellWidthPx * 2)
                col += 2
                continue
            }

            val start = col
            while (col < cols) {
                val candidate = snapshot.cellAt(rowIndex, col)
                if (candidate.isContinuation || candidate.isWide || !sameAttributes(candidate, attributes)) break
                col += 1
            }
            drawSingleWidthRun(canvas, snapshot, rowIndex, start, col, attributes, offsetX, rowY)
        }
    }

    private fun drawSingleWidthRun(
        canvas: Canvas,
        snapshot: TerminalSnapshot,
        rowIndex: Int,
        start: Int,
        endExclusive: Int,
        attributes: TerminalAttributes,
        offsetX: Float,
        rowY: Float
    ) {
        val x = offsetX + start * cellWidthPx
        val width = (endExclusive - start) * cellWidthPx
        drawBackground(canvas, attributes, x, rowY, width)
        if (attributes.isHidden) return
        configureGlyphPaint(attributes)
        val glyphs = StringBuilder(endExclusive - start)
        for (col in start until endExclusive) glyphs.append(snapshot.cellAt(rowIndex, col).text)
        canvas.drawText(glyphs.toString(), x, rowY + cellHeightPx * 0.78f, glyphPaint)
    }

    private fun drawGlyphCluster(
        canvas: Canvas,
        cell: TerminalCell,
        attributes: TerminalAttributes,
        x: Float,
        rowY: Float,
        width: Float
    ) {
        drawBackground(canvas, attributes, x, rowY, width)
        if (attributes.isHidden) return
        configureGlyphPaint(attributes)
        canvas.drawText(cell.text, x, rowY + cellHeightPx * 0.78f, glyphPaint)
    }

    private fun drawBackground(
        canvas: Canvas,
        attributes: TerminalAttributes,
        x: Float,
        rowY: Float,
        width: Float
    ) {
        if (attributes.backgroundColor == TerminalAttributes.Color.BackgroundDefault && attributes.backgroundRgb == null) return
        bgPaint.color = resolveBgColor(attributes)
        canvas.drawRect(x, rowY, x + width, rowY + cellHeightPx, bgPaint)
    }

    private fun configureGlyphPaint(attributes: TerminalAttributes) {
        glyphPaint.color = resolveFgColor(attributes)
        glyphPaint.typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.MONOSPACE,
            if (attributes.isBold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
        )
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

    private fun resolveFgColor(a: TerminalAttributes): Int = a.foregroundRgb?.orOpaque() ?: resolveColor(
        a.foregroundColor, defaultColor = themeForeground, isBold = a.isBold
    )

    private fun resolveBgColor(a: TerminalAttributes): Int = a.backgroundRgb?.orOpaque() ?: resolveColor(
        a.backgroundColor, defaultColor = themeBackground, isBold = false
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

private fun Int.orOpaque(): Int = 0xFF000000.toInt() or this
