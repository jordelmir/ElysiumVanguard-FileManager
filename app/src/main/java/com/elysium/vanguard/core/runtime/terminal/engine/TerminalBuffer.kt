package com.elysium.vanguard.core.runtime.terminal.engine

/**
 * PHASE 9.6.1 — The terminal screen model.
 *
 * A grid of `rows × cols` cells. Each cell carries the printable char
 * plus the [TerminalAttributes] in force when it was written. The parser
 * updates the cursor and the attributes; the renderer reads everything
 * exactly as it appears.
 *
 * Why a separate "attributes" object rather than a packed int? Two
 * reasons: (a) `withForeground` / `withBackground` returns an
 * immutable copy so attribute changes don't accidentally mutate other
 * cells; (b) reading colored cells in the renderer doesn't require
 * bit-twiddling. Memory-wise we pay ~24 bytes per cell: 80×24×24 ≈
 * 46 KB primary, 80×1000×24 ≈ 1.92 MB scrollback. Well under the
 * heap budget on stock devices.
 *
 * Why only a primary grid + a scrollback ring and not a "real" alt
 * buffer? `vim` and `less` do benefit, but they emit clear sequences
 * that fall back to the primary buffer cleanly. Alt buffer would let
 * us preserve scrollback on vim exit; deferred to 9.6.2.
 *
 * Phase 9.6.1 — first build; intentionally minimal.
 */
internal class TerminalBuffer(
    val cols: Int,
    val rows: Int,
    private val scrollbackLines: Int = DEFAULT_SCROLLBACK
) {
    /** The primary grid. Each entry is a (char, attributes) cell. */
    private val primary: Array<TerminalCell> = Array(rows * cols) {
        TerminalCell(' ', TerminalAttributes.DEFAULT)
    }

    /**
     * Pre-allocated scrollback ring of full rows. `head` points at the
     * next slot to write (oldest line lives at `(head - size) mod cap`).
     * Empty entries (null) represent virtual pre-history the user never
     * saw.
     */
    private val scrollback: Array<TerminalCellArray?>
    private val scrollbackCapacity: Int = scrollbackLines
    private var scrollbackHead = 0
    private var scrollbackSize = 0

    var cursorRow: Int = 0
        private set
    var cursorCol: Int = 0
        private set

    var currentAttributes: TerminalAttributes = TerminalAttributes.DEFAULT

    init {
        require(cols > 0 && rows > 0) { "grid must be non-empty" }
        require(scrollbackLines >= 0) { "scrollback must be non-negative" }
        scrollback = arrayOfNulls(scrollbackCapacity)
    }

    /** Write a printable character at the cursor and advance. */
    fun putChar(c: Char) {
        setCellRaw(cursorRow, cursorCol, TerminalCell(c, currentAttributes))
        advanceCursorAfterChar()
    }

    private fun advanceCursorAfterChar() {
        if (cursorCol + 1 < cols) {
            cursorCol += 1
            return
        }
        cursorCol = 0
        cursorDownOne()
    }

    fun lineFeed() = cursorDownOne()

    fun carriageReturn() {
        cursorCol = 0
    }

    fun backspace() {
        if (cursorCol > 0) cursorCol -= 1
    }

    fun horizontalTab() {
        cursorCol = ((cursorCol / TAB_WIDTH) + 1) * TAB_WIDTH
        if (cursorCol >= cols) cursorCol = cols - 1
    }

    /** Sets cursor to (1-based row, 1-based col); clipped to screen. */
    fun setCursorPosition(row: Int, col: Int) {
        cursorRow = (row - 1).coerceIn(0, rows - 1)
        cursorCol = (col - 1).coerceIn(0, cols - 1)
    }

    fun cursorUp(n: Int) {
        cursorRow = (cursorRow - n).coerceAtLeast(0)
    }

    fun cursorDown(n: Int) {
        cursorRow = (cursorRow + n).coerceAtMost(rows - 1)
    }

    fun cursorRight(n: Int) {
        cursorCol = (cursorCol + n).coerceAtMost(cols - 1)
    }

    fun cursorLeft(n: Int) {
        cursorCol = (cursorCol - n).coerceAtLeast(0)
    }

    fun eraseFromCursorToEndOfScreen() {
        eraseFromCursorToEndOfLine()
        for (r in (cursorRow + 1) until rows) {
            clearRow(r)
        }
    }

    fun eraseFromStartOfScreenToCursor() {
        for (r in 0 until cursorRow) clearRow(r)
        clearRowUpToInclusive(cursorRow, cursorCol)
    }

    fun eraseEntireScreen() {
        for (r in 0 until rows) clearRow(r)
    }

    fun eraseFromCursorToEndOfLine() {
        clearRowFrom(cursorRow, cursorCol)
    }

    fun eraseFromStartOfLineToCursor() {
        clearRowUpToInclusive(cursorRow, cursorCol)
    }

    fun eraseEntireLine() {
        clearRow(cursorRow)
    }

    private fun clearRow(row: Int) {
        val blank = TerminalCell(' ', currentAttributes)
        val start = row * cols
        for (c in 0 until cols) primary[start + c] = blank
    }

    private fun clearRowFrom(row: Int, fromCol: Int) {
        val blank = TerminalCell(' ', currentAttributes)
        val start = row * cols + fromCol
        for (c in fromCol until cols) primary[start + c - fromCol] = blank
        // Note: the inner expression above is intentionally verbose
        // for clarity; the JIT will collapse it.
    }

    private fun clearRowUpToInclusive(row: Int, col: Int) {
        val blank = TerminalCell(' ', currentAttributes)
        val start = row * cols
        val end = col.coerceAtMost(cols - 1)
        for (c in 0..end) primary[start + c] = blank
    }

    /** Wrap the top row into scrollback, shift everything up. */
    private fun scrollUpOne() {
        // A single-row display has nothing to scroll into. Without this
        // guard we used to silently blank the entire row when a wrap
        // reached the end (a real bug, see PHASE 9.6.1 unit tests).
        if (rows <= 1) return
        if (scrollbackCapacity > 0) {
            val top = topRow()
            scrollback[scrollbackHead] = top
            scrollbackHead = (scrollbackHead + 1) % scrollbackCapacity
            if (scrollbackSize < scrollbackCapacity) scrollbackSize += 1
        }
        // Shift primary up by one. arraycopy is hands-down faster than
        // touching every cell with a loop.
        System.arraycopy(primary, cols, primary, 0, (rows - 1) * cols)
        val bottom = (rows - 1) * cols
        val blank = TerminalCell(' ', currentAttributes)
        for (c in 0 until cols) primary[bottom + c] = blank
    }

    private fun cursorDownOne() {
        if (cursorRow + 1 < rows) {
            cursorRow += 1
            return
        }
        scrollUpOne()
    }

    private fun setCellRaw(row: Int, col: Int, cell: TerminalCell) {
        if (row in 0 until rows && col in 0 until cols) {
            primary[row * cols + col] = cell
        }
    }

    /**
     * Read-only cell access for the renderer. Cheap: just an
     * array-element read. Cells are immutable so no synchronization is
     * needed if the parser mutates the buffer; the only constraint is
     * that the renderer and parser run on different threads without
     * interleaving within a single frame, which Compose interleaves
     * for us.
     */
    fun cellAt(row: Int, col: Int): TerminalCell {
        return primary[row * cols + col]
    }

    fun primaryRows(): Int = rows
    fun primaryCols(): Int = cols

    private fun topRow(): TerminalCellArray {
        val r = TerminalCellArray(cols)
        System.arraycopy(primary, 0, r.cells, 0, cols)
        return r
    }

    /**
     * Returns the scrollback in newest-at-bottom order. Empty when
     * nothing has scrolled off yet.
     */
    fun scrollbackSnapshot(): Array<TerminalCellArray> {
        if (scrollbackSize == 0) return emptyArray()
        val out = ArrayList<TerminalCellArray>(scrollbackSize)
        val start = (scrollbackHead - scrollbackSize + scrollbackCapacity) % scrollbackCapacity
        for (i in 0 until scrollbackSize) {
            val idx = (start + i) % scrollbackCapacity
            val row = scrollback[idx] ?: continue
            out += row
        }
        return out.toTypedArray()
    }

    fun resize(newCols: Int, newRows: Int) {
        // 9.6.1: refuse silently if size unchanged; otherwise clear
        // primary. Proper reflow is 9.6.2 work.
        if (newCols == cols && newRows == rows) return
        for (i in primary.indices) primary[i] = TerminalCell(' ', currentAttributes)
    }

    companion object {
        const val DEFAULT_SCROLLBACK = 1000
        const val TAB_WIDTH = 8
    }
}

/**
 * One row of the terminal. Wraps a fixed-length [TerminalCell] array.
 * Renderer reads it cell-by-cell; scrollback stores one of these per
 * detached row.
 */
internal class TerminalCellArray(val cells: Array<TerminalCell>) {
    constructor(cols: Int) : this(Array(cols) { TerminalCell(' ', TerminalAttributes.DEFAULT) })

    fun cellAt(col: Int): TerminalCell = cells[col]
    val size: Int get() = cells.size
}

/** A single char + its current attributes. Used both in grid and scrollback. */
internal data class TerminalCell(val char: Char, val attributes: TerminalAttributes)
