package com.elysium.vanguard.core.runtime.terminal.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * PHASE 9.6.1 — Unit tests for the terminal grid + parser.
 *
 * The tests are deliberately small and fast. They run without Android
 * (no Robolectric, no Compose). Anything that needs the JVM Android
 * runtime moves to androidTest/ later.
 *
 * The tests follow a "given-when-then" structure so it's obvious which
 * method we exercise when reading. Phase 9.6.2 will add Android-side
 * surface view tests once the controller layer is in place.
 */
class TerminalBufferTest {

    @Test
    fun `putChar writes a cell and advances the cursor`() {
        val b = TerminalBuffer(cols = 10, rows = 2)
        b.putChar('a')
        assertEquals(0, b.cursorRow)
        assertEquals(1, b.cursorCol)
        assertEquals('a', b.cellAt(0, 0).char)
    }

    @Test
    fun `putChar wraps to next line at end of row`() {
        val b = TerminalBuffer(cols = 3, rows = 2)
        b.putChar('a')
        b.putChar('b')
        b.putChar('c')
        // After writing 'c' to (0,2) the cursor advances past the end
        // of the row and lands at (1, 0). The next putChar would write
        // to (1, 0), preserving 'c' at (0, 2).
        assertEquals(1, b.cursorRow)
        assertEquals(0, b.cursorCol)
        assertEquals('c', b.cellAt(0, 2).char)
    }

    @Test
    fun `lineFeed moves cursor down without changing col`() {
        val b = TerminalBuffer(cols = 5, rows = 3)
        b.putChar('a')
        val colAfterA = b.cursorCol
        b.lineFeed()
        assertEquals(colAfterA, b.cursorCol)
        assertEquals(1, b.cursorRow)
    }

    @Test
    fun `lineFeed at bottom scrolls the grid up`() {
        val b = TerminalBuffer(cols = 3, rows = 2)
        b.putChar('a')
        b.putChar('b')
        b.putChar('c')
        // After wrap we are at row 1, col 1 with 'c' written to row 0 col 2.
        // Now lineFeed at bottom row should rotate rows.
        b.lineFeed()
        // The top row (containing "abc") should be in scrollback and
        // the bottom row should be cleared.
        assertEquals(' ', b.cellAt(1, 0).char)
        assertEquals(' ', b.cellAt(1, 1).char)
    }

    @Test
    fun `carriageReturn moves cursor back to col 0`() {
        val b = TerminalBuffer(cols = 5, rows = 2)
        b.putChar('a')
        b.putChar('b')
        assertEquals(2, b.cursorCol)
        b.carriageReturn()
        assertEquals(0, b.cursorCol)
    }

    @Test
    fun `setCursorPosition is 1-based and clipped`() {
        val b = TerminalBuffer(cols = 4, rows = 3)
        b.setCursorPosition(2, 3)
        assertEquals(1, b.cursorRow)
        assertEquals(2, b.cursorCol)
        b.setCursorPosition(99, 99)
        // Clipped to last cell.
        assertEquals(2, b.cursorRow)
        assertEquals(3, b.cursorCol)
    }

    @Test
    fun `eraseEntireScreen blanks all cells`() {
        val b = TerminalBuffer(cols = 4, rows = 2)
        for (i in 0 until 8) b.putChar('x')
        b.eraseEntireScreen()
        for (r in 0 until 2) for (c in 0 until 4) {
            assertEquals(' ', b.cellAt(r, c).char)
        }
    }

    @Test
    fun `eraseFromCursorToEndOfLine clears only the right side`() {
        val b = TerminalBuffer(cols = 5, rows = 2)
        for (c in 'a'..'e') b.putChar(c)
        // After wrapping to (1,0), move the cursor back to (0, 2)
        // manually so we can exercise the partial erase.
        b.setCursorPosition(1, 3)
        b.eraseFromCursorToEndOfLine()
        assertEquals('a', b.cellAt(0, 0).char)
        assertEquals('b', b.cellAt(0, 1).char)
        assertEquals(' ', b.cellAt(0, 2).char)
        assertEquals(' ', b.cellAt(0, 4).char)
    }

    @Test
    fun `current attributes default to DEFAULT until SGR sets them`() {
        val b = TerminalBuffer(cols = 2, rows = 1)
        assertEquals(TerminalAttributes.DEFAULT, b.currentAttributes)
    }

    @Test
    fun `putChar applies current attributes to the written cell`() {
        val b = TerminalBuffer(cols = 2, rows = 1)
        val attrs = TerminalAttributes.DEFAULT
            .withForeground(TerminalAttributes.Color.Red)
            .withFlag(TerminalAttributes.Flag.BOLD, true)
        b.currentAttributes = attrs
        b.putChar('x')
        assertEquals(attrs, b.cellAt(0, 0).attributes)
    }

    @Test
    fun `withForeground returns a fresh copy`() {
        val original = TerminalAttributes.DEFAULT
        val updated = original.withForeground(TerminalAttributes.Color.Red)
        assertEquals(TerminalAttributes.Color.ForegroundDefault, original.foregroundColor)
        assertEquals(TerminalAttributes.Color.Red, updated.foregroundColor)
        assertNotEquals(original, updated)
    }

    @Test
    fun `withBackground returns a fresh copy`() {
        val original = TerminalAttributes.DEFAULT
        val updated = original.withBackground(TerminalAttributes.Color.Blue)
        assertEquals(TerminalAttributes.Color.BackgroundDefault, original.backgroundColor)
        assertEquals(TerminalAttributes.Color.Blue, updated.backgroundColor)
    }

    @Test
    fun `resize keeps same dimensions stable and reflows visible cells`() {
        val b = TerminalBuffer(cols = 4, rows = 2)
        b.putChar('a')
        // Same dims: nothing changes.
        b.resize(4, 2)
        assertEquals('a', b.cellAt(0, 0).char)
        // Different dimensions retain the visible terminal rather than
        // blanking it, which is essential on rotation/fold changes.
        b.resize(8, 4)
        assertEquals(8, b.primaryCols())
        assertEquals(4, b.primaryRows())
        assertEquals('a', b.cellAt(0, 0).char)
    }

    @Test
    fun `snapshot consumes dirty rows without exposing partial grid mutations`() {
        val b = TerminalBuffer(cols = 4, rows = 2)
        val initial = b.snapshot()
        assertEquals(2, initial.dirtyRows.size)
        b.putChar('x')
        val changed = b.snapshot()
        assertEquals(intArrayOf(0).toList(), changed.dirtyRows.toList())
        assertEquals('x', changed.cellAt(0, 0).char)
        assertEquals(0, b.snapshot().dirtyRows.size)
    }
}

class TerminalParserTest {

    @Test
    fun `fragmented UTF-8 bytes produce the same cells as contiguous input`() {
        val b = TerminalBuffer(cols = 8, rows = 1)
        val parser = TerminalParser(b)
        val bytes = "aéb".toByteArray(Charsets.UTF_8)
        parser.feed(bytes, 0, 2) // a + first byte of é
        parser.feed(bytes, 2, bytes.size - 2)

        assertEquals('a', b.cellAt(0, 0).char)
        assertEquals('é', b.cellAt(0, 1).char)
        assertEquals('b', b.cellAt(0, 2).char)
    }

    @Test
    fun `fragmented CSI bytes retain parser state`() {
        val b = TerminalBuffer(cols = 8, rows = 1)
        val parser = TerminalParser(b)
        parser.feed(byteArrayOf(0x1B))
        parser.feed("[31mR".toByteArray(Charsets.UTF_8))

        assertEquals('R', b.cellAt(0, 0).char)
        assertEquals(TerminalAttributes.Color.Red, b.cellAt(0, 0).attributes.foregroundColor)
    }

    @Test
    fun `every byte fragmentation boundary matches contiguous terminal state`() {
        val payload = "\u001b[31mR\u001b[0m é".toByteArray(Charsets.UTF_8)
        val expected = TerminalBuffer(cols = 16, rows = 2).also { buffer ->
            TerminalParser(buffer).feed(payload)
        }.snapshot()

        for (split in 0..payload.size) {
            val actualBuffer = TerminalBuffer(cols = 16, rows = 2)
            val parser = TerminalParser(actualBuffer)
            parser.feed(payload, 0, split)
            parser.feed(payload, split, payload.size - split)
            val actual = actualBuffer.snapshot()
            for (row in 0 until expected.rows) for (column in 0 until expected.cols) {
                assertEquals("split=$split row=$row column=$column", expected.cellAt(row, column), actual.cellAt(row, column))
            }
        }
    }

    @Test
    fun `plain ASCII writes go to the buffer`() {
        val b = TerminalBuffer(cols = 10, rows = 1)
        val parser = TerminalParser(b)
        parser.feed("hello")
        assertEquals('h', b.cellAt(0, 0).char)
        assertEquals('e', b.cellAt(0, 1).char)
        assertEquals('l', b.cellAt(0, 2).char)
        assertEquals('l', b.cellAt(0, 3).char)
        assertEquals('o', b.cellAt(0, 4).char)
    }

    @Test
    fun `carriage return resets column to 0`() {
        val b = TerminalBuffer(cols = 10, rows = 1)
        val parser = TerminalParser(b)
        parser.feed("hello")
        assertEquals(5, b.cursorCol)
        parser.feed("\r")
        assertEquals(0, b.cursorCol)
    }

    @Test
    fun `line feed advances column by 0 and row by 1`() {
        val b = TerminalBuffer(cols = 10, rows = 3)
        val parser = TerminalParser(b)
        parser.feed("ab\n")
        assertEquals(2, b.cursorCol) // CR not issued; col preserved
        assertEquals(1, b.cursorRow)
    }

    @Test
    fun `BS moves cursor back one column`() {
        val b = TerminalBuffer(cols = 10, rows = 1)
        val parser = TerminalParser(b)
        parser.feed("xyz")
        assertEquals(3, b.cursorCol)
        parser.feed("\b")
        assertEquals(2, b.cursorCol)
    }

    @Test
    fun `SGR 0 resets attributes to default`() {
        val b = TerminalBuffer(cols = 2, rows = 1)
        val parser = TerminalParser(b)
        b.currentAttributes = TerminalAttributes.DEFAULT
            .withForeground(TerminalAttributes.Color.Red)
        parser.feed("\u001b[0m")
        assertEquals(TerminalAttributes.DEFAULT, b.currentAttributes)
    }

    @Test
    fun `SGR 31 sets foreground to red`() {
        val b = TerminalBuffer(cols = 2, rows = 1)
        val parser = TerminalParser(b)
        parser.feed("\u001b[31m")
        assertEquals(TerminalAttributes.Color.Red, b.currentAttributes.foregroundColor)
    }

    @Test
    fun `SGR 1 sets bold flag`() {
        val b = TerminalBuffer(cols = 2, rows = 1)
        val parser = TerminalParser(b)
        parser.feed("\u001b[1m")
        assertEquals(true, b.currentAttributes.isBold)
    }

    @Test
    fun `SGR 22 clears bold`() {
        val b = TerminalBuffer(cols = 2, rows = 1)
        val parser = TerminalParser(b)
        parser.feed("\u001b[1m\u001b[22m")
        assertEquals(false, b.currentAttributes.isBold)
    }

    @Test
    fun `SGR 4 sets underline flag`() {
        val b = TerminalBuffer(cols = 2, rows = 1)
        val parser = TerminalParser(b)
        parser.feed("\u001b[4m")
        assertEquals(true, b.currentAttributes.isUnderline)
    }

    @Test
    fun `SGR truecolor preserves exact uniform foreground and background`() {
        val b = TerminalBuffer(cols = 4, rows = 1)
        val parser = TerminalParser(b)
        parser.feed("\u001b[38;2;57;255;20;48;2;3;9;17mN")

        assertEquals(0x39FF14, b.cellAt(0, 0).attributes.foregroundRgb)
        assertEquals(0x030911, b.cellAt(0, 0).attributes.backgroundRgb)
    }

    @Test
    fun `SGR 256 color resolves xterm cube values`() {
        val b = TerminalBuffer(cols = 4, rows = 1)
        val parser = TerminalParser(b)
        parser.feed("\u001b[38;5;196mR")

        assertEquals(0xFF0000, b.cellAt(0, 0).attributes.foregroundRgb)
    }

    @Test
    fun `CUP moves cursor 1-based`() {
        val b = TerminalBuffer(cols = 10, rows = 5)
        val parser = TerminalParser(b)
        parser.feed("\u001b[3;5H")
        assertEquals(2, b.cursorRow)
        assertEquals(4, b.cursorCol)
    }

    @Test
    fun `erase entire screen 2J clears buffer`() {
        val b = TerminalBuffer(cols = 4, rows = 1)
        val parser = TerminalParser(b)
        for (c in 'a'..'d') b.putChar(c)
        parser.feed("\u001b[2J")
        assertEquals(' ', b.cellAt(0, 0).char)
        assertEquals(' ', b.cellAt(0, 3).char)
    }

    @Test
    fun `cursor left and right move within a row`() {
        val b = TerminalBuffer(cols = 5, rows = 1)
        val parser = TerminalParser(b)
        for (c in 'a'..'c') b.putChar(c)
        assertEquals(3, b.cursorCol)
        parser.feed("\u001b[2D")
        assertEquals(1, b.cursorCol)
        parser.feed("\u001b[1C")
        assertEquals(2, b.cursorCol)
    }

    @Test
    fun `OSC string is dropped without affecting state`() {
        val b = TerminalBuffer(cols = 10, rows = 1)
        val parser = TerminalParser(b)
        parser.feed("hello\u001b]0;some title\u0007world")
        // "hello" fills cells 0..4. After OSC the parser returns to
        // ground state and "world" fills cells 5..9. The 'd' from
        // "world" lands at col 9 (the last column), not col 4.
        assertEquals('h', b.cellAt(0, 0).char)
        assertEquals('o', b.cellAt(0, 4).char)
        assertEquals('w', b.cellAt(0, 5).char)
        assertEquals('d', b.cellAt(0, 9).char)
    }

    @Test
    fun `OSC string terminator split across escape and slash does not print slash`() {
        val b = TerminalBuffer(cols = 8, rows = 1)
        val parser = TerminalParser(b)
        parser.feed("A\u001b]0;title\u001b")
        parser.feed("\\B")

        assertEquals('A', b.cellAt(0, 0).char)
        assertEquals('B', b.cellAt(0, 1).char)
        assertEquals(' ', b.cellAt(0, 2).char)
    }
}
