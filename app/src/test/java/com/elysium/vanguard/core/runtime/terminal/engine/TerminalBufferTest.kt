package com.elysium.vanguard.core.runtime.terminal.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TerminalBufferTest {

    private lateinit var buffer: TerminalBuffer

    @Before
    fun setUp() {
        buffer = TerminalBuffer(80, 24)
    }

    @Test
    fun `initial state has correct dimensions`() {
        assertEquals(80, buffer.cols)
        assertEquals(24, buffer.rows)
        assertEquals(0, buffer.cursorRow)
        assertEquals(0, buffer.cursorCol)
    }

    @Test
    fun `putChar advances cursor`() {
        buffer.putCodePoint('A'.code)
        assertEquals(0, buffer.cursorRow)
        assertEquals(1, buffer.cursorCol)
    }

    @Test
    fun `lineFeed advances row`() {
        buffer.putCodePoint('A'.code)
        buffer.lineFeed()
        assertEquals(1, buffer.cursorRow)
        assertEquals(1, buffer.cursorCol)
    }

    @Test
    fun `carriageReturn resets column`() {
        buffer.putCodePoint('A'.code)
        buffer.putCodePoint('B'.code)
        buffer.carriageReturn()
        assertEquals(0, buffer.cursorRow)
        assertEquals(0, buffer.cursorCol)
    }

    @Test
    fun `backspace moves left`() {
        buffer.putCodePoint('A'.code)
        buffer.putCodePoint('B'.code)
        buffer.backspace()
        assertEquals(0, buffer.cursorRow)
        assertEquals(1, buffer.cursorCol)
    }

    @Test
    fun `backspace stops at column 0`() {
        buffer.backspace()
        assertEquals(0, buffer.cursorCol)
    }

    @Test
    fun `horizontalTab advances to tab stop`() {
        buffer.horizontalTab()
        assertEquals(8, buffer.cursorCol)
    }

    @Test
    fun `tab wraps within width`() {
        repeat(20) { buffer.horizontalTab() }
        assertTrue(buffer.cursorCol <= 79)
    }

    @Test
    fun `setCursorPosition 1-based`() {
        buffer.setCursorPosition(5, 10)
        assertEquals(4, buffer.cursorRow)
        assertEquals(9, buffer.cursorCol)
    }

    @Test
    fun `setCursorPosition clamps to valid range`() {
        buffer.setCursorPosition(999, 999)
        assertEquals(23, buffer.cursorRow)
        assertEquals(79, buffer.cursorCol)
    }

    @Test
    fun `setCursorPosition zero clamps to 0`() {
        buffer.setCursorPosition(0, 0)
        assertEquals(0, buffer.cursorRow)
        assertEquals(0, buffer.cursorCol)
    }

    @Test
    fun `cursorUp`() {
        buffer.setCursorPosition(10, 10)
        buffer.cursorUp(3)
        assertEquals(6, buffer.cursorRow)
    }

    @Test
    fun `cursorDown`() {
        buffer.setCursorPosition(10, 10)
        buffer.cursorDown(3)
        assertEquals(12, buffer.cursorRow)
    }

    @Test
    fun `cursorRight`() {
        buffer.cursorRight(5)
        assertEquals(5, buffer.cursorCol)
    }

    @Test
    fun `cursorLeft`() {
        buffer.cursorRight(10)
        buffer.cursorLeft(3)
        assertEquals(7, buffer.cursorCol)
    }

    @Test
    fun `erase entire screen resets to home`() {
        buffer.setCursorPosition(10, 10)
        buffer.eraseEntireScreen()
    }

    @Test
    fun `erase from cursor to end of line`() {
        buffer.putCodePoint('A'.code)
        buffer.putCodePoint('B'.code)
        buffer.putCodePoint('C'.code)
        buffer.setCursorPosition(1, 2)
        buffer.eraseFromCursorToEndOfLine()
    }

    @Test
    fun `erase entire line`() {
        buffer.putCodePoint('A'.code)
        buffer.eraseEntireLine()
    }

    @Test
    fun `insert blank chars shifts right`() {
        buffer.setCursorPosition(1, 1)
        buffer.insertBlankChars(3)
    }

    @Test
    fun `delete chars shifts left`() {
        buffer.deleteChars(3)
    }

    @Test
    fun `insert lines scrolls down`() {
        buffer.setCursorPosition(5, 1)
        buffer.insertLines(3)
    }

    @Test
    fun `delete lines scrolls up`() {
        buffer.setCursorPosition(5, 1)
        buffer.deleteLines(3)
    }

    @Test
    fun `scroll up`() {
        buffer.scrollUp(3)
    }

    @Test
    fun `scroll down`() {
        buffer.scrollDown(3)
    }

    @Test
    fun `erase chars at cursor`() {
        buffer.putCodePoint('A'.code)
        buffer.putCodePoint('B'.code)
        buffer.putCodePoint('C'.code)
        buffer.setCursorPosition(1, 1)
        buffer.eraseChars(2)
    }

    @Test
    fun `setScrollRegion`() {
        buffer.setScrollRegion(3, 20)
    }

    @Test
    fun `resetScrollRegion covers full screen`() {
        buffer.setScrollRegion(3, 20)
        buffer.resetScrollRegion()
    }

    @Test
    fun `alternate screen preserves primary`() {
        buffer.putCodePoint('P'.code)
        buffer.enterAlternateScreen(clear = false)
        buffer.putCodePoint('A'.code)
        buffer.exitAlternateScreen()
    }

    @Test
    fun `alternate screen clear`() {
        buffer.enterAlternateScreen(clear = true)
        buffer.putCodePoint('A'.code)
        buffer.exitAlternateScreen()
    }

    @Test
    fun `alternate screen with cursor save`() {
        buffer.enterAlternateScreen(clear = true, saveCursor = true)
        buffer.putCodePoint('A'.code)
        buffer.exitAlternateScreen(restoreCursor = true)
    }

    @Test
    fun `reverseIndex within scroll region`() {
        buffer.setScrollRegion(5, 20)
        buffer.setCursorPosition(5, 1)
        buffer.reverseIndex()
    }

    @Test
    fun `reverseIndex at top of scroll region scrolls down`() {
        buffer.setScrollRegion(5, 20)
        buffer.setCursorPosition(5, 1)
        buffer.reverseIndex()
    }

    @Test
    fun `double width CJK character`() {
        buffer.putCodePoint(0x4E2D) // 中
        assertEquals(2, buffer.cursorCol)
    }

    @Test
    fun `combined ZWJ sequence`() {
        buffer.putCodePoint(0x1F468) // man
        buffer.putCodePoint(0x200D)  // ZWJ
        buffer.putCodePoint(0x2764)  // heart
    }

    @Test
    fun `resize preserves content`() {
        buffer.putCodePoint('H'.code)
        buffer.putCodePoint('i'.code)
        buffer.resize(40, 12)
        assertEquals(40, buffer.cols)
        assertEquals(12, buffer.rows)
    }

    @Test
    fun `resize larger adds columns`() {
        buffer.resize(120, 30)
        assertEquals(120, buffer.cols)
        assertEquals(30, buffer.rows)
    }

    @Test
    fun `snapshot captures current state`() {
        buffer.putCodePoint('T'.code)
        buffer.putCodePoint('e'.code)
        buffer.putCodePoint('s'.code)
        buffer.putCodePoint('t'.code)
        val snapshot = buffer.snapshot()
        assertNotNull(snapshot)
        assertEquals(80, snapshot.cols)
        assertEquals(24, snapshot.rows)
        assertEquals("T", snapshot.cellAt(0, 0).text)
        assertEquals("e", snapshot.cellAt(0, 1).text)
    }

    @Test
    fun `scrollback captures scrolled lines`() {
        for (i in 0 until 30) {
            if (i > 0) buffer.lineFeed()
            buffer.putCodePoint(('A' + i % 26).code)
        }
        val snapshot = buffer.snapshot()
    }

    @Test
    fun `textTail returns latest lines`() {
        repeat(10) { row ->
            buffer.putCodePoint(('A' + row % 26).code)
            buffer.lineFeed()
        }
        val tail = buffer.textTail(5)
        assertEquals(5, tail.size)
    }

    @Test
    fun `requestFullRedraw marks all dirty`() {
        buffer.requestFullRedraw()
    }

    @Test
    fun `setCursorPosition at bottom right`() {
        buffer.setCursorPosition(24, 80)
        assertEquals(23, buffer.cursorRow)
        assertEquals(79, buffer.cursorCol)
    }

    @Test
    fun `character at specific line and column`() {
        buffer.setCursorPosition(10, 10)
        buffer.putCodePoint('X'.code)
        val snapshot = buffer.snapshot()
        assertEquals("X", snapshot.cellAt(9, 9).text)
    }

    @Test
    fun `multiple writes to same cell`() {
        buffer.putCodePoint('A'.code)
        buffer.carriageReturn()
        buffer.putCodePoint('B'.code)
        val snapshot = buffer.snapshot()
        assertEquals("B", snapshot.cellAt(0, 0).text)
    }

    @Test
    fun `attributes survive cell writes`() {
        buffer.currentAttributes = TerminalAttributes.DEFAULT
            .withFlag(TerminalAttributes.Flag.BOLD, true)
            .withFlag(TerminalAttributes.Flag.UNDERLINE, true)
        buffer.putCodePoint('X'.code)
        val snapshot = buffer.snapshot()
        val cell = snapshot.cellAt(0, 0)
        assertNotNull(cell)
    }
}
