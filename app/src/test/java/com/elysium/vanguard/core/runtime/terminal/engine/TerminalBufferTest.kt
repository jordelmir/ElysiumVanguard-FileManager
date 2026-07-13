package com.elysium.vanguard.core.runtime.terminal.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `alternate screen restores untouched primary content after resize`() {
        val b = TerminalBuffer(cols = 6, rows = 2)
        b.putChar('m')
        b.putChar('a')
        b.putChar('i')
        b.putChar('n')
        b.enterAlternateScreen()
        b.putChar('v')
        b.putChar('i')
        b.putChar('m')
        b.resize(10, 3)
        assertTrue(b.isUsingAlternateScreen())
        assertEquals('v', b.cellAt(0, 0).char)

        b.exitAlternateScreen()
        assertFalse(b.isUsingAlternateScreen())
        assertEquals('m', b.cellAt(0, 0).char)
        assertEquals('n', b.cellAt(0, 3).char)
    }

    @Test
    fun `saved cursor keeps position and rendition across a resize`() {
        val b = TerminalBuffer(cols = 6, rows = 3)
        val savedAttributes = TerminalAttributes.DEFAULT
            .withForeground(TerminalAttributes.Color.BrightMagenta)
            .withFlag(TerminalAttributes.Flag.BOLD, true)
        b.setCursorPosition(2, 5)
        b.currentAttributes = savedAttributes
        b.saveCursor()

        b.setCursorPosition(1, 1)
        b.currentAttributes = TerminalAttributes.DEFAULT
        b.resize(newCols = 10, newRows = 4)
        b.restoreCursor()

        assertEquals(1, b.cursorRow)
        assertEquals(4, b.cursorCol)
        assertEquals(savedAttributes, b.currentAttributes)
    }

    @Test
    fun `scroll region preserves rows outside the margin`() {
        val b = TerminalBuffer(cols = 3, rows = 4)
        writeRow(b, 1, "AA")
        writeRow(b, 2, "BB")
        writeRow(b, 3, "CC")
        writeRow(b, 4, "DD")
        b.setScrollRegion(2, 3)
        b.setCursorPosition(3, 1)
        b.lineFeed()

        assertEquals('A', b.cellAt(0, 0).char)
        assertEquals('C', b.cellAt(1, 0).char)
        assertEquals(' ', b.cellAt(2, 0).char)
        assertEquals('D', b.cellAt(3, 0).char)
    }

    @Test
    fun `line and character edits stay within their VT boundaries`() {
        val b = TerminalBuffer(cols = 6, rows = 4)
        writeRow(b, 1, "AAAAA")
        writeRow(b, 2, "BBBBB")
        writeRow(b, 3, "CCCCC")
        writeRow(b, 4, "DDDDD")
        b.setScrollRegion(2, 4)
        b.setCursorPosition(2, 1)
        b.insertLines(1)
        assertEquals('A', b.cellAt(0, 0).char)
        assertEquals(' ', b.cellAt(1, 0).char)
        assertEquals('B', b.cellAt(2, 0).char)

        b.setCursorPosition(3, 3)
        b.deleteChars(2)
        assertEquals('B', b.cellAt(2, 0).char)
        assertEquals('B', b.cellAt(2, 1).char)
        assertEquals('B', b.cellAt(2, 2).char)
        assertEquals(' ', b.cellAt(2, 3).char)
        assertEquals(' ', b.cellAt(2, 4).char)
    }

    private fun writeRow(buffer: TerminalBuffer, row: Int, text: String) {
        buffer.setCursorPosition(row, 1)
        text.forEach(buffer::putChar)
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
    fun `fragmented emoji bytes preserve one double width cluster`() {
        val payload = "🙂X".toByteArray(Charsets.UTF_8)
        val expected = TerminalBuffer(cols = 6, rows = 1).also { TerminalParser(it).feed(payload) }.snapshot()

        for (split in 0..payload.size) {
            val buffer = TerminalBuffer(cols = 6, rows = 1)
            val parser = TerminalParser(buffer)
            parser.feed(payload, 0, split)
            parser.feed(payload, split, payload.size - split)
            val actual = buffer.snapshot()
            assertEquals("split=$split", expected.cells.toList(), actual.cells.toList())
            assertEquals("split=$split", expected.cursorCol, actual.cursorCol)
        }
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
    fun `CJK and emoji occupy two columns without corrupting following text`() {
        val b = TerminalBuffer(cols = 8, rows = 2)
        val parser = TerminalParser(b)
        parser.feed("A界B🙂C")

        assertEquals('A', b.cellAt(0, 0).char)
        assertEquals("界", b.cellAt(0, 1).text)
        assertTrue(b.cellAt(0, 2).isContinuation)
        assertEquals('B', b.cellAt(0, 3).char)
        assertEquals("🙂", b.cellAt(0, 4).text)
        assertTrue(b.cellAt(0, 5).isContinuation)
        assertEquals('C', b.cellAt(0, 6).char)
        assertEquals(7, b.cursorCol)
    }

    @Test
    fun `combining marks attach to prior glyph and do not consume a cell`() {
        val b = TerminalBuffer(cols = 6, rows = 1)
        val parser = TerminalParser(b)
        parser.feed("e\u0301X")

        assertEquals("e\u0301", b.cellAt(0, 0).text)
        assertEquals('X', b.cellAt(0, 1).char)
        assertEquals(2, b.cursorCol)
    }

    @Test
    fun `ZWJ emoji sequence remains one double width cluster`() {
        val b = TerminalBuffer(cols = 8, rows = 1)
        val parser = TerminalParser(b)
        parser.feed("👩\u200d💻X")

        assertEquals("👩\u200d💻", b.cellAt(0, 0).text)
        assertTrue(b.cellAt(0, 1).isContinuation)
        assertEquals('X', b.cellAt(0, 2).char)
        assertEquals(3, b.cursorCol)
    }

    @Test
    fun `writing on a wide continuation clears the old wide glyph`() {
        val b = TerminalBuffer(cols = 4, rows = 1)
        val parser = TerminalParser(b)
        parser.feed("界")
        b.setCursorPosition(1, 2)
        parser.feed("A")

        assertEquals(' ', b.cellAt(0, 0).char)
        assertEquals('A', b.cellAt(0, 1).char)
        assertFalse(b.cellAt(0, 1).isContinuation)
    }

    @Test
    fun `erasing a wide continuation clears the full glyph`() {
        val b = TerminalBuffer(cols = 4, rows = 1)
        val parser = TerminalParser(b)
        parser.feed("界")
        b.setCursorPosition(1, 2)
        b.eraseChars(1)

        assertEquals(' ', b.cellAt(0, 0).char)
        assertEquals(' ', b.cellAt(0, 1).char)
        assertFalse(b.cellAt(0, 1).isContinuation)
    }

    @Test
    fun `wide glyph at the final column wraps before rendering`() {
        val b = TerminalBuffer(cols = 4, rows = 2)
        val parser = TerminalParser(b)
        parser.feed("ABC界")

        assertEquals('C', b.cellAt(0, 2).char)
        assertEquals("界", b.cellAt(1, 0).text)
        assertTrue(b.cellAt(1, 1).isContinuation)
        assertEquals(1, b.cursorRow)
        assertEquals(2, b.cursorCol)
    }

    @Test
    fun `resize preserves wide cells and maps a full line to its next row`() {
        val b = TerminalBuffer(cols = 4, rows = 2)
        val parser = TerminalParser(b)
        parser.feed("界A")
        b.resize(newCols = 3, newRows = 2)

        assertEquals("界", b.cellAt(0, 0).text)
        assertTrue(b.cellAt(0, 1).isContinuation)
        assertEquals('A', b.cellAt(0, 2).char)
        assertEquals(1, b.cursorRow)
        assertEquals(0, b.cursorCol)

        parser.feed("B")
        assertEquals('B', b.cellAt(1, 0).char)
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

    @Test
    fun `OSC title updates are surfaced without rendering OSC bytes`() {
        val b = TerminalBuffer(cols = 8, rows = 1)
        val titles = mutableListOf<String>()
        val parser = TerminalParser(b, onTitleChanged = titles::add)
        parser.feed("A\u001b]2;vim — /etc/hosts\u0007B")

        assertEquals(listOf("vim — /etc/hosts"), titles)
        assertEquals('A', b.cellAt(0, 0).char)
        assertEquals('B', b.cellAt(0, 1).char)
    }

    @Test
    fun `DEC alternate screen mode keeps main terminal intact`() {
        val b = TerminalBuffer(cols = 8, rows = 2)
        val parser = TerminalParser(b)
        parser.feed("main")
        parser.feed("\u001b[?1049hvim")
        assertTrue(b.isUsingAlternateScreen())
        assertEquals('v', b.cellAt(0, 0).char)

        parser.feed("\u001b[?1049l")
        assertFalse(b.isUsingAlternateScreen())
        assertEquals('m', b.cellAt(0, 0).char)
        assertEquals('n', b.cellAt(0, 3).char)
    }

    @Test
    fun `DECSC and DECRC restore cursor and rendition`() {
        val b = TerminalBuffer(cols = 8, rows = 3)
        val parser = TerminalParser(b)
        parser.feed("\u001b[31m\u001b[2;")
        parser.feed("4H\u001b7\u001b[34m\u001b[1;1H\u001b")
        parser.feed("8X")

        assertEquals('X', b.cellAt(1, 3).char)
        assertEquals(1, b.cursorRow)
        assertEquals(4, b.cursorCol)
        assertEquals(TerminalAttributes.Color.Red, b.cellAt(1, 3).attributes.foregroundColor)
        assertEquals(TerminalAttributes.Color.Red, b.currentAttributes.foregroundColor)
    }

    @Test
    fun `DA and DSR queries receive conservative terminal responses`() {
        val b = TerminalBuffer(cols = 8, rows = 3)
        val responses = mutableListOf<String>()
        val parser = TerminalParser(
            buffer = b,
            onDeviceResponse = { bytes -> responses += String(bytes, Charsets.US_ASCII) }
        )
        parser.feed("\u001b[c\u001b[>c\u001b[2;5H\u001b[5n\u001b[")
        parser.feed("6n\u001b[?6n")

        assertEquals(
            listOf("\u001b[?1;0c", "\u001b[>0;0;0c", "\u001b[0n", "\u001b[2;5R", "\u001b[?2;5R"),
            responses
        )
        assertEquals(' ', b.cellAt(1, 4).char)
    }

    @Test
    fun `DEC 1048 and 1049 restore primary cursor state`() {
        val b = TerminalBuffer(cols = 8, rows = 3)
        val parser = TerminalParser(b)
        parser.feed("\u001b[32m\u001b[3;2H\u001b[?1048h\u001b[1;1H\u001b[?1048lY")
        assertEquals('Y', b.cellAt(2, 1).char)
        assertEquals(TerminalAttributes.Color.Green, b.cellAt(2, 1).attributes.foregroundColor)

        parser.feed("\u001b[2;5H\u001b[?1049hvim\u001b[?1049lZ")
        assertFalse(b.isUsingAlternateScreen())
        assertEquals('Z', b.cellAt(1, 4).char)
        assertEquals(TerminalAttributes.Color.Green, b.cellAt(1, 4).attributes.foregroundColor)
    }

    @Test
    fun `DEC 47 preserves alternate contents between buffer switches`() {
        val b = TerminalBuffer(cols = 8, rows = 2)
        val parser = TerminalParser(b)
        parser.feed("\u001b[?47hvim\u001b[?47l\u001b[?47h")

        assertTrue(b.isUsingAlternateScreen())
        assertEquals('v', b.cellAt(0, 0).char)
    }

    @Test
    fun `DEC input modes are published and reset independently`() {
        val parser = TerminalParser(TerminalBuffer(cols = 8, rows = 2))
        parser.feed("\u001b[?1h\u001b[?2004h")
        assertTrue(parser.inputModes().applicationCursorKeys)
        assertTrue(parser.inputModes().bracketedPaste)

        parser.feed("\u001b[?1l")
        assertFalse(parser.inputModes().applicationCursorKeys)
        assertTrue(parser.inputModes().bracketedPaste)
    }

    @Test
    fun `DECSTBM and reverse index operate only on the declared margins`() {
        val b = TerminalBuffer(cols = 3, rows = 4)
        val parser = TerminalParser(b)
        parser.feed("AA\r\nBB\r\nCC\r\nDD")
        parser.feed("\u001b[2;3r\u001b[2;1H\u001bM")

        assertEquals('A', b.cellAt(0, 0).char)
        assertEquals(' ', b.cellAt(1, 0).char)
        assertEquals('B', b.cellAt(2, 0).char)
        assertEquals('D', b.cellAt(3, 0).char)
    }
}
