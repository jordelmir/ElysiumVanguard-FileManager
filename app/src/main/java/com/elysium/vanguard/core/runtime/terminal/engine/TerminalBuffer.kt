package com.elysium.vanguard.core.runtime.terminal.engine

import java.util.ArrayDeque

/**
 * Mutable terminal screen model with bounded scrollback and frame snapshots.
 *
 * Parser mutations happen on a PTY I/O coroutine while Canvas rendering runs
 * elsewhere. A renderer therefore consumes one immutable [TerminalSnapshot]
 * rather than observing a grid while it is being resized or scrolled.
 */
internal class TerminalBuffer(
    cols: Int,
    rows: Int,
    private val scrollbackLines: Int = DEFAULT_SCROLLBACK
) {
    var cols: Int = cols
        private set
    var rows: Int = rows
        private set

    private var primary: Array<TerminalCell> = blankGrid(cols, rows)
    private val scrollback = ArrayDeque<TerminalCellArray>(scrollbackLines)
    private var dirtyRows: BooleanArray = BooleanArray(rows) { true }
    private var fullRedraw = true

    var cursorRow: Int = 0
        private set
    var cursorCol: Int = 0
        private set
    var currentAttributes: TerminalAttributes = TerminalAttributes.DEFAULT

    init {
        require(cols > 0 && rows > 0) { "grid must be non-empty" }
        require(scrollbackLines >= 0) { "scrollback must be non-negative" }
    }

    @Synchronized
    fun putChar(c: Char) {
        setCellRaw(cursorRow, cursorCol, TerminalCell(c, currentAttributes))
        advanceCursorAfterChar()
    }

    @Synchronized
    fun lineFeed() = cursorDownOne()

    @Synchronized
    fun carriageReturn() = setCursor(cursorRow, 0)

    @Synchronized
    fun backspace() = setCursor(cursorRow, (cursorCol - 1).coerceAtLeast(0))

    @Synchronized
    fun horizontalTab() = setCursor(cursorRow, (((cursorCol / TAB_WIDTH) + 1) * TAB_WIDTH).coerceAtMost(cols - 1))

    /** Sets cursor to a one-based VT coordinate and clips it to the grid. */
    @Synchronized
    fun setCursorPosition(row: Int, col: Int) {
        setCursor((row - 1).coerceIn(0, rows - 1), (col - 1).coerceIn(0, cols - 1))
    }

    @Synchronized
    fun cursorUp(n: Int) = setCursor((cursorRow - n.coerceAtLeast(1)).coerceAtLeast(0), cursorCol)

    @Synchronized
    fun cursorDown(n: Int) = setCursor((cursorRow + n.coerceAtLeast(1)).coerceAtMost(rows - 1), cursorCol)

    @Synchronized
    fun cursorRight(n: Int) = setCursor(cursorRow, (cursorCol + n.coerceAtLeast(1)).coerceAtMost(cols - 1))

    @Synchronized
    fun cursorLeft(n: Int) = setCursor(cursorRow, (cursorCol - n.coerceAtLeast(1)).coerceAtLeast(0))

    @Synchronized
    fun eraseFromCursorToEndOfScreen() {
        eraseFromCursorToEndOfLine()
        for (row in (cursorRow + 1) until rows) clearRow(row)
    }

    @Synchronized
    fun eraseFromStartOfScreenToCursor() {
        for (row in 0 until cursorRow) clearRow(row)
        clearRowUpToInclusive(cursorRow, cursorCol)
    }

    @Synchronized
    fun eraseEntireScreen() {
        for (row in 0 until rows) clearRow(row)
    }

    @Synchronized
    fun eraseFromCursorToEndOfLine() = clearRowFrom(cursorRow, cursorCol)

    @Synchronized
    fun eraseFromStartOfLineToCursor() = clearRowUpToInclusive(cursorRow, cursorCol)

    @Synchronized
    fun eraseEntireLine() = clearRow(cursorRow)

    /**
     * Reflows visible rows into a new mutable grid without clearing terminal
     * history. Rows are conservative hard boundaries because this model does
     * not yet retain DEC wrap flags; content is never silently discarded except
     * the oldest visible rows when shrinking height.
     */
    @Synchronized
    fun resize(newCols: Int, newRows: Int) {
        require(newCols > 0 && newRows > 0) { "grid must be non-empty" }
        if (newCols == cols && newRows == rows) return

        val oldCursorRow = cursorRow
        val oldCursorCol = cursorCol
        val visibleRows = ArrayList<TerminalCellArray>(rows)
        for (row in 0 until rows) visibleRows += rowCopy(row)
        val reflowed = reflowRows(visibleRows, newCols)
        val retained = reflowed.takeLast(newRows)
        val skipped = reflowed.size - retained.size

        cols = newCols
        rows = newRows
        primary = blankGrid(newCols, newRows)
        // Keep the existing top-left viewport stable while a larger display
        // becomes available. When shrinking, `takeLast` has already removed
        // the oldest rows and the retained viewport still starts at row zero.
        val firstTargetRow = 0
        retained.forEachIndexed { index, row ->
            System.arraycopy(row.cells, 0, primary, (firstTargetRow + index) * newCols, newCols)
        }

        val rowsBeforeCursor = reflowRows(visibleRows.take(oldCursorRow), newCols).size
        val cursorChunk = oldCursorCol / newCols
        val mappedRow = rowsBeforeCursor + cursorChunk - skipped + firstTargetRow
        val mappedCol = oldCursorCol % newCols
        cursorRow = mappedRow.coerceIn(0, newRows - 1)
        cursorCol = mappedCol.coerceIn(0, newCols - 1)

        // Preserve history but normalize row width for consumers that inspect
        // scrollback after a fold/unfold resize.
        val reflowedHistory = reflowRows(scrollback.toList(), newCols)
        scrollback.clear()
        reflowedHistory.takeLast(scrollbackLines).forEach(scrollback::addLast)
        dirtyRows = BooleanArray(newRows) { true }
        fullRedraw = true
    }

    @Synchronized
    fun cellAt(row: Int, col: Int): TerminalCell {
        require(row in 0 until rows && col in 0 until cols) { "cell outside terminal grid" }
        return primary[row * cols + col]
    }

    @Synchronized
    fun primaryRows(): Int = rows

    @Synchronized
    fun primaryCols(): Int = cols

    /** Immutable frame state. Calling this consumes accumulated dirty rows. */
    @Synchronized
    fun snapshot(): TerminalSnapshot {
        val dirty = if (fullRedraw) IntArray(rows) { it } else {
            dirtyRows.indices.filter { dirtyRows[it] }.toIntArray()
        }
        val snapshot = TerminalSnapshot(
            cols = cols,
            rows = rows,
            cursorRow = cursorRow,
            cursorCol = cursorCol,
            cells = primary.copyOf(),
            dirtyRows = dirty,
            fullRedraw = fullRedraw
        )
        dirtyRows.fill(false)
        fullRedraw = false
        return snapshot
    }

    @Synchronized
    fun requestFullRedraw() {
        fullRedraw = true
        dirtyRows.fill(true)
    }

    @Synchronized
    fun scrollbackSnapshot(): Array<TerminalCellArray> = scrollback.toTypedArray()

    private fun advanceCursorAfterChar() {
        if (cursorCol + 1 < cols) {
            setCursor(cursorRow, cursorCol + 1)
        } else {
            setCursor(cursorRow, 0)
            cursorDownOne()
        }
    }

    private fun cursorDownOne() {
        if (cursorRow + 1 < rows) setCursor(cursorRow + 1, cursorCol)
        else scrollUpOne()
    }

    private fun scrollUpOne() {
        // A one-row terminal cannot shift content upward. Keeping the row is
        // the least surprising VT behavior and avoids erasing the last glyph
        // every time output wraps at the right edge.
        if (rows <= 1) {
            dirtyRows[0] = true
            return
        }
        if (scrollbackLines > 0) {
            if (scrollback.size == scrollbackLines) scrollback.removeFirst()
            scrollback.addLast(rowCopy(0))
        }
        System.arraycopy(primary, cols, primary, 0, (rows - 1) * cols)
        val blank = TerminalCell(' ', currentAttributes)
        val bottom = (rows - 1) * cols
        for (column in 0 until cols) primary[bottom + column] = blank
        dirtyRows.fill(true)
        // Cursor stays on the bottom row, retaining its column.
        cursorRow = rows - 1
        dirtyRows[cursorRow] = true
    }

    private fun setCursor(row: Int, column: Int) {
        val previousRow = cursorRow
        cursorRow = row
        cursorCol = column
        dirtyRows[previousRow] = true
        dirtyRows[row] = true
    }

    private fun setCellRaw(row: Int, col: Int, cell: TerminalCell) {
        primary[row * cols + col] = cell
        dirtyRows[row] = true
    }

    private fun clearRow(row: Int) {
        val blank = TerminalCell(' ', currentAttributes)
        val start = row * cols
        for (column in 0 until cols) primary[start + column] = blank
        dirtyRows[row] = true
    }

    private fun clearRowFrom(row: Int, fromCol: Int) {
        val blank = TerminalCell(' ', currentAttributes)
        val start = row * cols
        for (column in fromCol until cols) primary[start + column] = blank
        dirtyRows[row] = true
    }

    private fun clearRowUpToInclusive(row: Int, column: Int) {
        val blank = TerminalCell(' ', currentAttributes)
        val start = row * cols
        for (col in 0..column.coerceAtMost(cols - 1)) primary[start + col] = blank
        dirtyRows[row] = true
    }

    private fun rowCopy(row: Int): TerminalCellArray {
        val cells = Array(cols) { index -> primary[row * cols + index] }
        return TerminalCellArray(cells)
    }

    private fun reflowRows(source: List<TerminalCellArray>, targetCols: Int): List<TerminalCellArray> {
        val result = ArrayList<TerminalCellArray>()
        source.forEach { row ->
            val lastMeaningful = row.cells.indexOfLast {
                it.char != ' ' || it.attributes.backgroundColor != TerminalAttributes.Color.BackgroundDefault
            }
            if (lastMeaningful < 0) {
                result += TerminalCellArray(targetCols)
                return@forEach
            }
            var offset = 0
            while (offset <= lastMeaningful) {
                val cells = Array(targetCols) { TerminalCell(' ', TerminalAttributes.DEFAULT) }
                val count = minOf(targetCols, lastMeaningful - offset + 1)
                for (index in 0 until count) cells[index] = row.cells[offset + index]
                result += TerminalCellArray(cells)
                offset += count
            }
        }
        return result
    }

    private fun blankGrid(columns: Int, rowCount: Int): Array<TerminalCell> =
        Array(columns * rowCount) { TerminalCell(' ', TerminalAttributes.DEFAULT) }

    companion object {
        const val DEFAULT_SCROLLBACK = 1_000
        const val TAB_WIDTH = 8
    }
}

internal data class TerminalSnapshot(
    val cols: Int,
    val rows: Int,
    val cursorRow: Int,
    val cursorCol: Int,
    val cells: Array<TerminalCell>,
    val dirtyRows: IntArray,
    val fullRedraw: Boolean
) {
    fun cellAt(row: Int, col: Int): TerminalCell = cells[row * cols + col]
}

/** One immutable row used by scrollback and resize reflow. */
internal class TerminalCellArray(val cells: Array<TerminalCell>) {
    constructor(cols: Int) : this(Array(cols) { TerminalCell(' ', TerminalAttributes.DEFAULT) })

    fun cellAt(col: Int): TerminalCell = cells[col]
    val size: Int get() = cells.size
}

/** A single glyph cell plus its immutable rendition attributes. */
internal data class TerminalCell(val char: Char, val attributes: TerminalAttributes)
