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

    private var primaryScreen = ScreenState(blankGrid(cols, rows), scrollBottom = rows - 1)
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
    fun putChar(c: Char) = putCodePoint(c.code)

    /**
     * Writes one Unicode code point as a terminal cell cluster. Double-width
     * glyphs reserve a continuation cell; combining marks and emoji joiners
     * extend the previous cluster without advancing the cursor.
     */
    @Synchronized
    fun putCodePoint(codePoint: Int) {
        require(Character.isValidCodePoint(codePoint)) { "invalid Unicode code point" }
        val glyph = String(Character.toChars(codePoint))
        when (TerminalCharacterWidth.columns(codePoint)) {
            TerminalCharacterWidth.ZERO -> {
                if (!appendToPreviousCluster(glyph)) writeGlyph("\u25cc$glyph", TerminalCharacterWidth.SINGLE)
            }
            TerminalCharacterWidth.DOUBLE -> {
                if (!appendToJoinerCluster(glyph)) writeGlyph(glyph, TerminalCharacterWidth.DOUBLE)
            }
            else -> writeGlyph(glyph, TerminalCharacterWidth.SINGLE)
        }
    }

    @Synchronized fun lineFeed() = cursorDownOne()
    @Synchronized fun reverseIndex() {
        val screen = activeScreen
        if (screen.cursorRow == screen.scrollTop) scrollDownRegion(screen.scrollTop, screen.scrollBottom, 1)
        else if (screen.cursorRow > 0) setCursor(screen.cursorRow - 1, screen.cursorCol)
    }
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

    /** DECSTBM: sets a one-based inclusive scroll region and homes the cursor. */
    @Synchronized
    fun setScrollRegion(top: Int = 1, bottom: Int = rows) {
        val normalizedTop = (top - 1).coerceIn(0, rows - 1)
        val normalizedBottom = (bottom - 1).coerceIn(0, rows - 1)
        if (normalizedTop >= normalizedBottom) return
        activeScreen.scrollTop = normalizedTop
        activeScreen.scrollBottom = normalizedBottom
        setCursor(0, 0)
    }

    @Synchronized fun resetScrollRegion() = setScrollRegion(1, rows)

    /** IL — inserts blank lines at the cursor within the active scroll region. */
    @Synchronized
    fun insertLines(count: Int) {
        val screen = activeScreen
        if (cursorRow !in screen.scrollTop..screen.scrollBottom) return
        val actual = count.coerceAtLeast(1).coerceAtMost(screen.scrollBottom - cursorRow + 1)
        for (row in screen.scrollBottom downTo cursorRow + actual) copyRow(screen, row - actual, row)
        for (row in cursorRow until cursorRow + actual) blankRow(screen, row)
        markDirtyRows(cursorRow, screen.scrollBottom)
    }

    /** DL — deletes lines at the cursor within the active scroll region. */
    @Synchronized
    fun deleteLines(count: Int) {
        val screen = activeScreen
        if (cursorRow !in screen.scrollTop..screen.scrollBottom) return
        val actual = count.coerceAtLeast(1).coerceAtMost(screen.scrollBottom - cursorRow + 1)
        for (row in cursorRow..screen.scrollBottom - actual) copyRow(screen, row + actual, row)
        for (row in screen.scrollBottom - actual + 1..screen.scrollBottom) blankRow(screen, row)
        markDirtyRows(cursorRow, screen.scrollBottom)
    }

    /** ICH — shifts cells right and inserts blanks without modifying other rows. */
    @Synchronized
    fun insertBlankChars(count: Int) {
        val actual = count.coerceAtLeast(1).coerceAtMost(cols - cursorCol)
        val screen = activeScreen
        val rowStart = cursorRow * cols
        for (column in cols - 1 downTo cursorCol + actual) {
            screen.cells[rowStart + column] = screen.cells[rowStart + column - actual]
        }
        val blank = TerminalCell(' ', currentAttributes)
        for (column in cursorCol until cursorCol + actual) screen.cells[rowStart + column] = blank
        normalizeRow(screen, cursorRow)
        dirtyRows[cursorRow] = true
    }

    /** DCH — shifts cells left and clears the exposed right-hand cells. */
    @Synchronized
    fun deleteChars(count: Int) {
        val actual = count.coerceAtLeast(1).coerceAtMost(cols - cursorCol)
        val screen = activeScreen
        val rowStart = cursorRow * cols
        for (column in cursorCol until cols - actual) {
            screen.cells[rowStart + column] = screen.cells[rowStart + column + actual]
        }
        val blank = TerminalCell(' ', currentAttributes)
        for (column in cols - actual until cols) screen.cells[rowStart + column] = blank
        normalizeRow(screen, cursorRow)
        dirtyRows[cursorRow] = true
    }

    /** ECH — erases cells from the cursor without shifting text. */
    @Synchronized
    fun eraseChars(count: Int) {
        val actual = count.coerceAtLeast(1).coerceAtMost(cols - cursorCol)
        val blank = TerminalCell(' ', currentAttributes)
        val rowStart = cursorRow * cols
        for (column in cursorCol until cursorCol + actual) {
            clearWideGlyphAt(activeScreen, cursorRow, column)
            activeScreen.cells[rowStart + column] = blank
        }
        normalizeRow(activeScreen, cursorRow)
        dirtyRows[cursorRow] = true
    }

    @Synchronized fun scrollUp(count: Int) = scrollUpRegion(activeScreen.scrollTop, activeScreen.scrollBottom, count)
    @Synchronized fun scrollDown(count: Int) = scrollDownRegion(activeScreen.scrollTop, activeScreen.scrollBottom, count)

    /**
     * Saves the cursor and the active SGR state for DECSC / DECRC and DEC
     * private modes 1048/1049. The saved state is owned by each screen: a
     * full-screen program must never overwrite the caller's saved main-screen
     * cursor while it is using the alternate buffer.
     */
    @Synchronized
    fun saveCursor() {
        val screen = activeScreen
        screen.savedCursorRow = screen.cursorRow
        screen.savedCursorCol = screen.cursorCol
        screen.savedAttributes = screen.attributes
    }

    /** Restores the cursor and SGR state previously captured by [saveCursor]. */
    @Synchronized
    fun restoreCursor() {
        val screen = activeScreen
        setCursor(screen.savedCursorRow.coerceIn(0, rows - 1), screen.savedCursorCol.coerceIn(0, cols - 1))
        screen.attributes = screen.savedAttributes
    }

    /** Enters DEC alternate screen; primary cells and scrollback remain intact. */
    @Synchronized
    fun enterAlternateScreen(clear: Boolean = true, saveCursor: Boolean = false) {
        if (activeScreen !== primaryScreen) return
        if (saveCursor) saveCursor()
        val alternate = if (clear || alternateScreen == null) {
            ScreenState(blankGrid(cols, rows), scrollBottom = rows - 1)
        } else {
            alternateScreen!!
        }
        alternateScreen = alternate
        activeScreen = alternate
        markAllDirty()
    }

    /** Restores the primary screen and optionally discards the alternate grid. */
    @Synchronized
    fun exitAlternateScreen(restoreCursor: Boolean = false, discard: Boolean = true) {
        if (activeScreen === primaryScreen) return
        activeScreen = primaryScreen
        if (restoreCursor) restoreCursor()
        if (discard) alternateScreen = null
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

    private fun writeGlyph(glyph: String, requestedWidth: Int) {
        val width = if (requestedWidth == TerminalCharacterWidth.DOUBLE && cols > 1) {
            TerminalCharacterWidth.DOUBLE
        } else {
            TerminalCharacterWidth.SINGLE
        }
        if (width == TerminalCharacterWidth.DOUBLE && cursorCol == cols - 1) {
            setCursor(cursorRow, 0)
            cursorDownOne()
        }

        val screen = activeScreen
        clearWideGlyphAt(screen, cursorRow, cursorCol)
        setCellRaw(cursorRow, cursorCol, TerminalCell(glyph, currentAttributes, width))
        if (width == TerminalCharacterWidth.DOUBLE) {
            setCellRaw(cursorRow, cursorCol + 1, TerminalCell.continuation(currentAttributes))
            advanceCursorAfterWideGlyph()
        } else {
            advanceCursorAfterChar()
        }
    }

    private fun appendToPreviousCluster(suffix: String): Boolean {
        val (row, col) = previousClusterPosition() ?: return false
        val screen = activeScreen
        val index = row * cols + col
        screen.cells[index] = screen.cells[index].copy(text = screen.cells[index].text + suffix)
        dirtyRows[row] = true
        return true
    }

    private fun appendToJoinerCluster(glyph: String): Boolean {
        val (row, col) = previousClusterPosition() ?: return false
        val screen = activeScreen
        val index = row * cols + col
        val previous = screen.cells[index]
        if (!previous.text.endsWith('\u200d')) return false
        screen.cells[index] = previous.copy(text = previous.text + glyph)
        dirtyRows[row] = true
        return true
    }

    private fun previousClusterPosition(): Pair<Int, Int>? {
        var row = cursorRow
        var col = cursorCol - 1
        if (col < 0) {
            if (row == 0) return null
            row -= 1
            col = cols - 1
        }
        val screen = activeScreen
        if (screen.cells[row * cols + col].isContinuation) col -= 1
        if (col < 0) return null
        val cell = screen.cells[row * cols + col]
        return if (cell.isContinuation) null else row to col
    }

    private fun clearWideGlyphAt(screen: ScreenState, row: Int, col: Int) {
        val cell = screen.cells[row * cols + col]
        val leadCol = when {
            cell.isWide -> col
            cell.isContinuation && col > 0 && screen.cells[row * cols + col - 1].isWide -> col - 1
            else -> return
        }
        val blank = TerminalCell(' ', currentAttributes)
        screen.cells[row * cols + leadCol] = blank
        if (leadCol + 1 < cols) screen.cells[row * cols + leadCol + 1] = blank
        dirtyRows[row] = true
    }

    private fun advanceCursorAfterChar() {
        if (cursorCol + 1 < cols) setCursor(cursorRow, cursorCol + 1)
        else {
            setCursor(cursorRow, 0)
            cursorDownOne()
        }
    }

    private fun advanceCursorAfterWideGlyph() {
        if (cursorCol + 2 < cols) setCursor(cursorRow, cursorCol + 2)
        else {
            setCursor(cursorRow, 0)
            cursorDownOne()
        }
    }

    private fun cursorDownOne() {
        val screen = activeScreen
        when {
            screen.cursorRow == screen.scrollBottom -> scrollUpRegion(screen.scrollTop, screen.scrollBottom, 1)
            screen.cursorRow + 1 < rows -> setCursor(screen.cursorRow + 1, screen.cursorCol)
        }
    }

    private fun scrollUpRegion(top: Int, bottom: Int, count: Int) {
        val screen = activeScreen
        if (top == bottom) {
            // A one-row terminal has nowhere to scroll. Retaining its last
            // glyph matches established VT behavior and avoids erasing text
            // merely because the cursor wrapped at the right edge.
            dirtyRows[top] = true
            return
        }
        val actual = count.coerceAtLeast(1).coerceAtMost(bottom - top + 1)
        repeat(actual) {
            if (top == 0 && bottom == rows - 1 && screen === primaryScreen && scrollbackLines > 0) {
                if (scrollback.size == scrollbackLines) scrollback.removeFirst()
                scrollback.addLast(rowCopy(screen, 0))
            }
            for (row in top until bottom) copyRow(screen, row + 1, row)
            blankRow(screen, bottom)
        }
        markDirtyRows(top, bottom)
    }

    private fun scrollDownRegion(top: Int, bottom: Int, count: Int) {
        val screen = activeScreen
        if (top == bottom) {
            dirtyRows[top] = true
            return
        }
        val actual = count.coerceAtLeast(1).coerceAtMost(bottom - top + 1)
        repeat(actual) {
            for (row in bottom downTo top + 1) copyRow(screen, row - 1, row)
            blankRow(screen, top)
        }
        markDirtyRows(top, bottom)
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
        clearWideGlyphAt(activeScreen, row, fromCol)
        for (column in fromCol until cols) activeScreen.cells[start + column] = blank
        normalizeRow(activeScreen, row)
        dirtyRows[row] = true
    }

    private fun clearRowUpToInclusive(row: Int, column: Int) {
        val blank = TerminalCell(' ', currentAttributes)
        val start = row * cols
        clearWideGlyphAt(activeScreen, row, column.coerceAtMost(cols - 1))
        for (col in 0..column.coerceAtMost(cols - 1)) activeScreen.cells[start + col] = blank
        normalizeRow(activeScreen, row)
        dirtyRows[row] = true
    }

    private fun copyRow(screen: ScreenState, source: Int, target: Int) {
        System.arraycopy(screen.cells, source * cols, screen.cells, target * cols, cols)
    }

    private fun blankRow(screen: ScreenState, row: Int) {
        val start = row * cols
        val blank = TerminalCell(' ', screen.attributes)
        for (column in 0 until cols) screen.cells[start + column] = blank
    }

    /** Repairs only malformed halves created by editing inside a wide cell. */
    private fun normalizeRow(screen: ScreenState, row: Int) {
        var col = 0
        while (col < cols) {
            val index = row * cols + col
            val cell = screen.cells[index]
            when {
                cell.isWide -> {
                    if (col + 1 >= cols || !screen.cells[index + 1].isContinuation) {
                        // Preserve the glyph instead of silently deleting it
                        // after an ICH/DCH operation split the pair.
                        screen.cells[index] = cell.copy(columnWidth = TerminalCharacterWidth.SINGLE)
                        col += 1
                    } else {
                        col += TerminalCharacterWidth.DOUBLE
                    }
                }
                cell.isContinuation -> {
                    val hasLead = col > 0 && screen.cells[index - 1].isWide
                    if (!hasLead) screen.cells[index] = TerminalCell(' ', cell.attributes)
                    col += 1
                }
                else -> col += 1
            }
        }
    }

    private fun markDirtyRows(first: Int, last: Int) {
        for (row in first..last) dirtyRows[row] = true
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
        fun reflowCursor(row: Int, col: Int): Pair<Int, Int> {
            val rowsBeforeCursor = reflowRows(visibleRows.take(row), targetCols).size
            return (rowsBeforeCursor + col / targetCols - skipped)
                .coerceIn(0, targetRows - 1) to (col % targetCols).coerceIn(0, targetCols - 1)
        }
        val (cursorRow, cursorCol) = reflowCursor(screen.cursorRow, screen.cursorCol)
        val (savedCursorRow, savedCursorCol) = reflowCursor(screen.savedCursorRow, screen.savedCursorCol)
        return ScreenState(
            cells = cells,
            cursorRow = cursorRow,
            cursorCol = cursorCol,
            attributes = screen.attributes,
            scrollTop = 0,
            scrollBottom = targetRows - 1,
            savedCursorRow = savedCursorRow,
            savedCursorCol = savedCursorCol,
            savedAttributes = screen.savedAttributes
        )
    }

    private fun rowCopy(screen: ScreenState, row: Int): TerminalCellArray =
        TerminalCellArray(Array(cols) { column -> screen.cells[row * cols + column] })

    private fun reflowRows(source: List<TerminalCellArray>, targetCols: Int): List<TerminalCellArray> {
        val result = ArrayList<TerminalCellArray>()
        source.forEach { row ->
            val lastMeaningful = row.cells.indexOfLast {
                !it.isContinuation && (it.text != " " ||
                    it.attributes.backgroundColor != TerminalAttributes.Color.BackgroundDefault)
            }
            if (lastMeaningful < 0) {
                result += TerminalCellArray(targetCols)
                return@forEach
            }

            var target = Array(targetCols) { TerminalCell(' ', TerminalAttributes.DEFAULT) }
            var targetCol = 0
            var emitted = false
            fun emitTarget() {
                result += TerminalCellArray(target)
                target = Array(targetCols) { TerminalCell(' ', TerminalAttributes.DEFAULT) }
                targetCol = 0
                emitted = true
            }
            fun append(cell: TerminalCell) {
                val width = if (cell.isWide && targetCols > 1) {
                    TerminalCharacterWidth.DOUBLE
                } else {
                    TerminalCharacterWidth.SINGLE
                }
                if (width == TerminalCharacterWidth.DOUBLE && targetCol == targetCols - 1) emitTarget()
                target[targetCol] = cell.copy(columnWidth = width)
                if (width == TerminalCharacterWidth.DOUBLE) {
                    target[targetCol + 1] = TerminalCell.continuation(cell.attributes)
                }
                targetCol += width
                if (targetCol == targetCols) emitTarget()
            }

            for (index in 0..lastMeaningful) {
                val cell = row.cells[index]
                if (!cell.isContinuation) append(cell)
            }
            if (targetCol > 0 || !emitted) result += TerminalCellArray(target)
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
        var attributes: TerminalAttributes = TerminalAttributes.DEFAULT,
        var scrollTop: Int = 0,
        var scrollBottom: Int = 0,
        var savedCursorRow: Int = 0,
        var savedCursorCol: Int = 0,
        var savedAttributes: TerminalAttributes = TerminalAttributes.DEFAULT
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

/** A rendered terminal cluster. A wide lead owns the following continuation. */
internal data class TerminalCell(
    val text: String,
    val attributes: TerminalAttributes,
    val columnWidth: Int = TerminalCharacterWidth.SINGLE
) {
    constructor(char: Char, attributes: TerminalAttributes) : this(char.toString(), attributes)

    /** Compatibility accessor for ASCII-oriented callers and tests. */
    val char: Char get() = text.firstOrNull() ?: ' '
    val isContinuation: Boolean get() = columnWidth == TerminalCharacterWidth.ZERO
    val isWide: Boolean get() = columnWidth == TerminalCharacterWidth.DOUBLE

    companion object {
        fun continuation(attributes: TerminalAttributes): TerminalCell =
            TerminalCell(text = "", attributes = attributes, columnWidth = TerminalCharacterWidth.ZERO)
    }
}
