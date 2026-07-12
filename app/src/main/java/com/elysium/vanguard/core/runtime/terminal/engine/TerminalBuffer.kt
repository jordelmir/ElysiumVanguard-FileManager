package com.elysium.vanguard.core.runtime.terminal.engine

import java.util.ArrayDeque

/**
 * Mutable terminal model with a primary screen, alternate screen, bounded
 * primary scrollback and immutable renderer snapshots.
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

    private var primaryScreen = ScreenState(blankGrid(cols, rows))
    private var alternateScreen: ScreenState? = null
    private var activeScreen: ScreenState = primaryScreen
    private val scrollback = ArrayDeque<TerminalCellArray>(scrollbackLines)
    private var dirtyRows = BooleanArray(rows) { true }
    private var fullRedraw = true

    val cursorRow: Int get() = activeScreen.cursorRow
    val cursorCol: Int get() = activeScreen.cursorCol
    var currentAttributes: TerminalAttributes
        get() = activeScreen.attributes
        set(value) { activeScreen.attributes = value }

    init {
        require(cols > 0 && rows > 0) { "grid must be non-empty" }
        require(scrollbackLines >= 0) { "scrollback must be non-negative" }
    }

    @Synchronized
    fun putChar(c: Char) {
        setCellRaw(cursorRow, cursorCol, TerminalCell(c, currentAttributes))
        advanceCursorAfterChar()
    }

    @Synchronized fun lineFeed() = cursorDownOne()
    @Synchronized fun carriageReturn() = setCursor(cursorRow, 0)
    @Synchronized fun backspace() = setCursor(cursorRow, (cursorCol - 1).coerceAtLeast(0))
    @Synchronized fun horizontalTab() =
        setCursor(cursorRow, (((cursorCol / TAB_WIDTH) + 1) * TAB_WIDTH).coerceAtMost(cols - 1))

    @Synchronized
    fun setCursorPosition(row: Int, col: Int) =
        setCursor((row - 1).coerceIn(0, rows - 1), (col - 1).coerceIn(0, cols - 1))

    @Synchronized fun cursorUp(n: Int) = setCursor((cursorRow - n.coerceAtLeast(1)).coerceAtLeast(0), cursorCol)
    @Synchronized fun cursorDown(n: Int) = setCursor((cursorRow + n.coerceAtLeast(1)).coerceAtMost(rows - 1), cursorCol)
    @Synchronized fun cursorRight(n: Int) = setCursor(cursorRow, (cursorCol + n.coerceAtLeast(1)).coerceAtMost(cols - 1))
    @Synchronized fun cursorLeft(n: Int) = setCursor(cursorRow, (cursorCol - n.coerceAtLeast(1)).coerceAtLeast(0))

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

    @Synchronized fun eraseEntireScreen() { for (row in 0 until rows) clearRow(row) }
    @Synchronized fun eraseFromCursorToEndOfLine() = clearRowFrom(cursorRow, cursorCol)
    @Synchronized fun eraseFromStartOfLineToCursor() = clearRowUpToInclusive(cursorRow, cursorCol)
    @Synchronized fun eraseEntireLine() = clearRow(cursorRow)

    /** Enters DEC alternate screen; primary cells and scrollback remain intact. */
    @Synchronized
    fun enterAlternateScreen(clear: Boolean = true) {
        val alternate = if (clear || alternateScreen == null) {
            ScreenState(blankGrid(cols, rows))
        } else {
            alternateScreen!!
        }
        alternateScreen = alternate
        activeScreen = alternate
        markAllDirty()
    }

    /** Restores the primary screen and discards the transient alternate grid. */
    @Synchronized
    fun exitAlternateScreen() {
        if (activeScreen === primaryScreen) return
        activeScreen = primaryScreen
        alternateScreen = null
        markAllDirty()
    }

    @Synchronized
    fun isUsingAlternateScreen(): Boolean = activeScreen !== primaryScreen

    /** Reflows both screens so fold/rotation does not corrupt a hidden main screen. */
    @Synchronized
    fun resize(newCols: Int, newRows: Int) {
        require(newCols > 0 && newRows > 0) { "grid must be non-empty" }
        if (newCols == cols && newRows == rows) return

        val activeWasAlternate = activeScreen !== primaryScreen
        val resizedPrimary = reflowScreen(primaryScreen, newCols, newRows)
        val resizedAlternate = alternateScreen?.let { reflowScreen(it, newCols, newRows) }
        val reflowedHistory = reflowRows(scrollback.toList(), newCols)

        cols = newCols
        rows = newRows
        primaryScreen = resizedPrimary
        alternateScreen = resizedAlternate
        activeScreen = if (activeWasAlternate && resizedAlternate != null) resizedAlternate else resizedPrimary
        scrollback.clear()
        reflowedHistory.takeLast(scrollbackLines).forEach(scrollback::addLast)
        dirtyRows = BooleanArray(newRows) { true }
        fullRedraw = true
    }

    @Synchronized
    fun cellAt(row: Int, col: Int): TerminalCell {
        require(row in 0 until rows && col in 0 until cols) { "cell outside terminal grid" }
        return activeScreen.cells[row * cols + col]
    }

    @Synchronized fun primaryRows(): Int = rows
    @Synchronized fun primaryCols(): Int = cols

    /** Immutable frame state. Calling this consumes the dirty-row accumulator. */
    @Synchronized
    fun snapshot(): TerminalSnapshot {
        val dirty = if (fullRedraw) IntArray(rows) { it }
        else dirtyRows.indices.filter { dirtyRows[it] }.toIntArray()
        val snapshot = TerminalSnapshot(
            cols = cols,
            rows = rows,
            cursorRow = cursorRow,
            cursorCol = cursorCol,
            cells = activeScreen.cells.copyOf(),
            dirtyRows = dirty,
            fullRedraw = fullRedraw
        )
        dirtyRows.fill(false)
        fullRedraw = false
        return snapshot
    }

    @Synchronized fun requestFullRedraw() = markAllDirty()
    @Synchronized fun scrollbackSnapshot(): Array<TerminalCellArray> = scrollback.toTypedArray()

    private fun advanceCursorAfterChar() {
        if (cursorCol + 1 < cols) setCursor(cursorRow, cursorCol + 1)
        else {
            setCursor(cursorRow, 0)
            cursorDownOne()
        }
    }

    private fun cursorDownOne() {
        if (cursorRow + 1 < rows) setCursor(cursorRow + 1, cursorCol) else scrollUpOne()
    }

    private fun scrollUpOne() {
        if (rows <= 1) {
            dirtyRows[0] = true
            return
        }
        val screen = activeScreen
        if (screen === primaryScreen && scrollbackLines > 0) {
            if (scrollback.size == scrollbackLines) scrollback.removeFirst()
            scrollback.addLast(rowCopy(screen, 0))
        }
        System.arraycopy(screen.cells, cols, screen.cells, 0, (rows - 1) * cols)
        val blank = TerminalCell(' ', currentAttributes)
        val bottom = (rows - 1) * cols
        for (column in 0 until cols) screen.cells[bottom + column] = blank
        screen.cursorRow = rows - 1
        dirtyRows.fill(true)
    }

    private fun setCursor(row: Int, column: Int) {
        val screen = activeScreen
        dirtyRows[screen.cursorRow] = true
        screen.cursorRow = row
        screen.cursorCol = column
        dirtyRows[row] = true
    }

    private fun setCellRaw(row: Int, col: Int, cell: TerminalCell) {
        activeScreen.cells[row * cols + col] = cell
        dirtyRows[row] = true
    }

    private fun clearRow(row: Int) {
        val blank = TerminalCell(' ', currentAttributes)
        val start = row * cols
        for (column in 0 until cols) activeScreen.cells[start + column] = blank
        dirtyRows[row] = true
    }

    private fun clearRowFrom(row: Int, fromCol: Int) {
        val blank = TerminalCell(' ', currentAttributes)
        val start = row * cols
        for (column in fromCol until cols) activeScreen.cells[start + column] = blank
        dirtyRows[row] = true
    }

    private fun clearRowUpToInclusive(row: Int, column: Int) {
        val blank = TerminalCell(' ', currentAttributes)
        val start = row * cols
        for (col in 0..column.coerceAtMost(cols - 1)) activeScreen.cells[start + col] = blank
        dirtyRows[row] = true
    }

    private fun reflowScreen(screen: ScreenState, targetCols: Int, targetRows: Int): ScreenState {
        val visibleRows = ArrayList<TerminalCellArray>(rows)
        for (row in 0 until rows) visibleRows += rowCopy(screen, row)
        val reflowed = reflowRows(visibleRows, targetCols)
        val retained = reflowed.takeLast(targetRows)
        val skipped = reflowed.size - retained.size
        val cells = blankGrid(targetCols, targetRows)
        retained.forEachIndexed { index, row ->
            System.arraycopy(row.cells, 0, cells, index * targetCols, targetCols)
        }
        val rowsBeforeCursor = reflowRows(visibleRows.take(screen.cursorRow), targetCols).size
        val cursorRow = (rowsBeforeCursor + screen.cursorCol / targetCols - skipped)
            .coerceIn(0, targetRows - 1)
        return ScreenState(
            cells = cells,
            cursorRow = cursorRow,
            cursorCol = (screen.cursorCol % targetCols).coerceIn(0, targetCols - 1),
            attributes = screen.attributes
        )
    }

    private fun rowCopy(screen: ScreenState, row: Int): TerminalCellArray =
        TerminalCellArray(Array(cols) { column -> screen.cells[row * cols + column] })

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

    private fun markAllDirty() {
        fullRedraw = true
        dirtyRows.fill(true)
    }

    private fun blankGrid(columns: Int, rowCount: Int): Array<TerminalCell> =
        Array(columns * rowCount) { TerminalCell(' ', TerminalAttributes.DEFAULT) }

    private data class ScreenState(
        var cells: Array<TerminalCell>,
        var cursorRow: Int = 0,
        var cursorCol: Int = 0,
        var attributes: TerminalAttributes = TerminalAttributes.DEFAULT
    )

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

internal class TerminalCellArray(val cells: Array<TerminalCell>) {
    constructor(cols: Int) : this(Array(cols) { TerminalCell(' ', TerminalAttributes.DEFAULT) })
    fun cellAt(col: Int): TerminalCell = cells[col]
    val size: Int get() = cells.size
}

internal data class TerminalCell(val char: Char, val attributes: TerminalAttributes)
