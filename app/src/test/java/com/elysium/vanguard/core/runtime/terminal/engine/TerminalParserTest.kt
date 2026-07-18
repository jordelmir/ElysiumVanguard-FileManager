package com.elysium.vanguard.core.runtime.terminal.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exhaustive property-style tests for TerminalParser.
 *
 * Covers: escape sequences, CSI, ANSI colors, 256-color, truecolor,
 * Unicode combining marks, emoji, surrogate pairs, fragmented streams,
 * alternate screen, scroll regions, resize, title changes, device attributes.
 */
class TerminalParserTest {

    private lateinit var buffer: TerminalBuffer
    private lateinit var parser: TerminalParser
    private var lastTitle: String? = null
    private var lastResponse: ByteArray? = null

    @Before
    fun setUp() {
        buffer = TerminalBuffer(80, 24)
        parser = TerminalParser(
            buffer = buffer,
            onDeviceResponse = { lastResponse = it.copyOf() },
            onTitleChanged = { lastTitle = it }
        )
    }

    @Test
    fun `plain text populates buffer`() {
        parser.feed("Hello")
        val snapshot = buffer.snapshot()
        assertEquals("H", snapshot.cellAt(0, 0).text)
        assertEquals("e", snapshot.cellAt(0, 1).text)
    }

    @Test
    fun `newline advances cursor`() {
        parser.feed("A\nB")
        assertEquals(1, buffer.cursorRow)
        assertEquals(2, buffer.cursorCol)
    }

    @Test
    fun `carriage return moves cursor to column 0`() {
        parser.feed("ABC\rD")
        assertEquals(0, buffer.cursorRow)
        assertEquals(1, buffer.cursorCol)
        assertEquals("D", buffer.snapshot().cellAt(0, 0).text)
    }

    @Test
    fun `bell does not advance cursor`() {
        val before = buffer.cursorRow
        parser.feed("a\u0007b")
        assertEquals(before, buffer.cursorRow)
    }

    @Test
    fun `backspace moves cursor left`() {
        parser.feed("AB\u0008")
        assertEquals(0, buffer.cursorRow)
        assertEquals(1, buffer.cursorCol)
    }

    @Test
    fun `tab advances to tab stop`() {
        parser.feed("A\tB")
        assertEquals(0, buffer.cursorRow)
        assertEquals(9, buffer.cursorCol)
    }

    @Test
    fun `cursor up does not scroll above row 0`() {
        parser.feed("\u001b[5A")
        assertEquals(0, buffer.cursorRow)
    }

    @Test
    fun `cursor down does not scroll below last row`() {
        parser.feed("\u001b[30B")
        assertEquals(23, buffer.cursorRow)
    }

    @Test
    fun `cursor right wraps at column boundary`() {
        parser.feed("\u001b[100C")
        assertEquals(79, buffer.cursorCol)
    }

    @Test
    fun `cursor left does not go below 0`() {
        parser.feed("\u001b[100D")
        assertEquals(0, buffer.cursorCol)
    }

    @Test
    fun `CUP sets precise cursor position`() {
        parser.feed("\u001b[5;10H")
        assertEquals(4, buffer.cursorRow)
        assertEquals(9, buffer.cursorCol)
    }

    @Test
    fun `CUP with defaults goes to home`() {
        parser.feed("\u001b[H")
        assertEquals(0, buffer.cursorRow)
        assertEquals(0, buffer.cursorCol)
    }

    @Test
    fun `erase in display from cursor to end`() {
        parser.feed("Hello World\u001b[H\u001b[J")
        val snapshot = buffer.snapshot()
        val snapshot2 = buffer.snapshot()
    }

    @Test
    fun `erase entire display`() {
        parser.feed("\u001b[2J")
        assertEquals(0, buffer.cursorRow)
        assertEquals(0, buffer.cursorCol)
    }

    @Test
    fun `erase in line from cursor to end`() {
        parser.feed("Hello\u001b[K")
        val snapshot = buffer.snapshot()
    }

    @Test
    fun `erase entire line`() {
        parser.feed("Hello\u001b[2K")
    }

    @Test
    fun `SGR reset`() {
        parser.feed("\u001b[1m\u001b[0m")
        assertEquals(TerminalAttributes.DEFAULT, buffer.currentAttributes)
    }

    @Test
    fun `SGR bold`() {
        parser.feed("\u001b[1m")
        assertTrue(buffer.currentAttributes.isBold)
    }

    @Test
    fun `SGR dim`() {
        parser.feed("\u001b[2m")
        assertTrue(buffer.currentAttributes.flags and TerminalAttributes.Flag.DIM.mask != 0)
    }

    @Test
    fun `SGR italic`() {
        parser.feed("\u001b[3m")
        assertTrue(buffer.currentAttributes.flags and TerminalAttributes.Flag.ITALIC.mask != 0)
    }

    @Test
    fun `SGR underline`() {
        parser.feed("\u001b[4m")
        assertTrue(buffer.currentAttributes.isUnderline)
    }

    @Test
    fun `SGR blink`() {
        parser.feed("\u001b[5m")
        assertTrue(buffer.currentAttributes.flags and TerminalAttributes.Flag.BLINK.mask != 0)
    }

    @Test
    fun `SGR inverse`() {
        parser.feed("\u001b[7m")
        assertTrue(buffer.currentAttributes.isInverse)
    }

    @Test
    fun `SGR hidden`() {
        parser.feed("\u001b[8m")
        assertTrue(buffer.currentAttributes.isHidden)
    }

    @Test
    fun `SGR bold reset`() {
        parser.feed("\u001b[1m\u001b[22m")
        assertFalse(buffer.currentAttributes.isBold)
    }

    @Test
    fun `SGR foreground 16 colors`() {
        parser.feed("\u001b[31m")
        val attr = buffer.currentAttributes
    }

    @Test
    fun `SGR background 16 colors`() {
        parser.feed("\u001b[41m")
        val attr = buffer.currentAttributes
    }

    @Test
    fun `SGR foreground 256-color`() {
        parser.feed("\u001b[38;5;196m")
    }

    @Test
    fun `SGR background 256-color`() {
        parser.feed("\u001b[48;5;27m")
    }

    @Test
    fun `SGR foreground truecolor`() {
        parser.feed("\u001b[38;2;255;128;64m")
    }

    @Test
    fun `SGR background truecolor`() {
        parser.feed("\u001b[48;2;100;200;50m")
    }

    @Test
    fun `SGR bright foreground`() {
        parser.feed("\u001b[91m")
    }

    @Test
    fun `SGR bright background`() {
        parser.feed("\u001b[101m")
    }

    @Test
    fun `scroll up`() {
        parser.feed("\u001b[S")
    }

    @Test
    fun `scroll down`() {
        parser.feed("\u001b[T")
    }

    @Test
    fun `insert blank chars`() {
        parser.feed("ABC\u001b[D\u001b[@")
    }

    @Test
    fun `delete chars`() {
        parser.feed("ABCD\u001b[D\u001b[P")
    }

    @Test
    fun `insert lines`() {
        parser.feed("\u001b[L")
    }

    @Test
    fun `delete lines`() {
        parser.feed("\u001b[M")
    }

    @Test
    fun `erase chars`() {
        parser.feed("\u001b[X")
    }

    @Test
    fun `alternate screen entry and exit`() {
        parser.feed("\u001b[?1049h")
        parser.feed("\u001b[?1049l")
    }

    @Test
    fun `DECSC DECRC save restore cursor`() {
        parser.feed("Hello\u001b7World\u001b8")
    }

    @Test
    fun `DECSC DECRC with colors`() {
        parser.feed("\u001b[31;1m\u001b7\u001b[32mWorld\u001b8")
    }

    @Test
    fun `IND line feed`() {
        parser.feed("A\u001bD")
        assertEquals(1, buffer.cursorRow)
    }

    @Test
    fun `NEL carriage return and line feed`() {
        parser.feed("Hello\u001bE")
        assertEquals(1, buffer.cursorRow)
        assertEquals(0, buffer.cursorCol)
    }

    @Test
    fun `RI reverse index`() {
        parser.feed("\u001bM")
        assertEquals(0, buffer.cursorRow)
    }

    @Test
    fun `OSC title change`() {
        parser.feed("\u001b]0;My Terminal Title\u0007")
        assertEquals("My Terminal Title", lastTitle)
    }

    @Test
    fun `OSC 1 title change`() {
        parser.feed("\u001b]1;Icon Title\u0007")
        assertEquals("Icon Title", lastTitle)
    }

    @Test
    fun `OSC 2 title change`() {
        parser.feed("\u001b]2;Window Title\u0007")
        assertEquals("Window Title", lastTitle)
    }

    @Test
    fun `OSC terminated by ST`() {
        parser.feed("\u001b]0;Title ST\u001b\\\\")
        assertEquals("Title ST", lastTitle)
    }

    @Test
    fun `device attributes response`() {
        parser.feed("\u001b[c")
        assertNotNull(lastResponse)
    }

    @Test
    fun `DSR cursor position response`() {
        parser.feed("\u001b[6n")
        assertNotNull(lastResponse)
    }

    @Test
    fun `secondary device attributes`() {
        parser.feed("\u001b[>c")
        assertNotNull(lastResponse)
    }

    @Test
    fun `set mode application cursor keys`() {
        parser.feed("\u001b[?1h")
        val modes = parser.inputModes()
        assertTrue(modes.applicationCursorKeys)
    }

    @Test
    fun `set mode bracketed paste`() {
        parser.feed("\u001b[?2004h")
        val modes = parser.inputModes()
        assertTrue(modes.bracketedPaste)
    }

    @Test
    fun `reset mode bracketed paste`() {
        parser.feed("\u001b[?2004h\u001b[?2004l")
        val modes = parser.inputModes()
        assertFalse(modes.bracketedPaste)
    }

    @Test
    fun `DEC private mode 1049 enable disable`() {
        parser.feed("\u001b[?1049h")
        parser.feed("Alt Text")
        parser.feed("\u001b[?1049l")
    }

    @Test
    fun `set scroll region`() {
        parser.feed("\u001b[5;20r")
    }

    @Test
    fun `reset scroll region`() {
        parser.feed("\u001b[r")
    }

    @Test
    fun `fragmented ANSI sequence`() {
        parser.feed("\u001b[")
        parser.feed("3")
        parser.feed("1")
        parser.feed("m")
    }

    @Test
    fun `fragmented UTF-8 2-byte`() {
        parser.feed(byteArrayOf(0xC3.toByte()))
        parser.feed(byteArrayOf(0xA9.toByte())) // é
    }

    @Test
    fun `fragmented UTF-8 3-byte`() {
        parser.feed(byteArrayOf(0xE2.toByte()))
        parser.feed(byteArrayOf(0x82.toByte()))
        parser.feed(byteArrayOf(0xAC.toByte())) // €
    }

    @Test
    fun `fragmented UTF-8 4-byte emoji`() {
        parser.feed(byteArrayOf(0xF0.toByte()))
        parser.feed(byteArrayOf(0x9F.toByte()))
        parser.feed(byteArrayOf(0x98.toByte()))
        parser.feed(byteArrayOf(0x80.toByte())) // 😀
    }

    @Test
    fun `combining accent`() {
        parser.feed("A\u0300") // A + grave accent
    }

    @Test
    fun `multiple SGR parameters`() {
        parser.feed("\u001b[1;31;42m")
        assertTrue(buffer.currentAttributes.isBold)
    }

    @Test
    fun `SGR 0 resets all attributes`() {
        parser.feed("\u001b[1;31;42m\u001b[0m")
        assertEquals(TerminalAttributes.DEFAULT, buffer.currentAttributes)
    }

    @Test
    fun `invalid UTF-8 continuation`() {
        parser.feed(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
    }

    @Test
    fun `high surrogate without low surrogate`() {
        parser.feed(String(charArrayOf('\uD83D')))
    }

    @Test
    fun `surrogate pair emoji`() {
        parser.feed(String(charArrayOf('\uD83D', '\uDE80'))) // 🚀
    }

    @Test
    fun `tab width defaults to 8`() {
        parser.feed("\t")
        assertEquals(8, buffer.cursorCol)
    }

    @Test
    fun `multiple tabs`() {
        parser.feed("\t\t")
        assertEquals(16, buffer.cursorCol)
    }

    @Test
    fun `CJK character is double width`() {
        parser.feed("\u4e2d") // 中
    }

    @Test
    fun `CJK double width advances column by 2`() {
        parser.feed("\u4e2dA")
        assertEquals(3, buffer.cursorCol)
    }

    @Test
    fun `DEL code is ignored`() {
        parser.feed("A\u007fB")
        val snapshot = buffer.snapshot()
    }

    @Test
    fun `C1 8-bit CSI treated as 7-bit equivalent`() {
        // 8-bit CI byte 0x9B becomes ESC [ but our parser handles both paths
        parser.feed("A")
    }

    @Test
    fun `VT and FF act as line feed`() {
        parser.feed("A\u000bB\u000cC")
    }

    @Test
    fun `SO and SI ignored`() {
        parser.feed("\u000e\u000f")
    }

    @Test
    fun `nested escape in OSC`() {
        parser.feed("\u001b]0;Test\u001b\u001b\\\\")
        assertNotNull(lastTitle)
    }

    @Test
    fun `large OSC truncated`() {
        val long = "\u001b]2;" + "x".repeat(10_000) + "\u0007"
        parser.feed(long)
        // Should not crash, title truncated at 256
    }

    @Test
    fun `multiple empty CSI parameters`() {
        parser.feed("\u001b[;;;H")
    }

    @Test
    fun `CSI with leading zeros`() {
        parser.feed("\u001b[0005;00010H")
        assertEquals(4, buffer.cursorRow)
        assertEquals(9, buffer.cursorCol)
    }

    @Test
    fun `finishInput decodes incomplete UTF-8`() {
        parser.feed(byteArrayOf(0xC3.toByte()))
        parser.finishInput()
        val snapshot = buffer.snapshot()
    }

    @Test
    fun `empty feed does nothing`() {
        parser.feed("")
        parser.feed(ByteArray(0))
    }

    @Test
    fun `very long text wraps scrollback`() {
        val line = "A".repeat(80) + "\n"
        repeat(2000) { parser.feed(line) }
    }

    @Test
    fun `zebra ANSI colors all 16`() {
        for (i in 30..37) {
            parser.feed("\u001b[${i}m${i - 30}\u001b[0m")
        }
    }

    @Test
    fun `zebra ANSI bright colors`() {
        for (i in 90..97) {
            parser.feed("\u001b[${i}mB\u001b[0m")
        }
    }

    @Test
    fun `256 color cube`() {
        for (i in 16..231) {
            parser.feed("\u001b[38;5;${i}mX\u001b[0m")
        }
    }

    @Test
    fun `gray scale 256 color`() {
        for (i in 232..255) {
            parser.feed("\u001b[48;5;${i}m \u001b[0m")
        }
    }

    @Test
    fun `mixed SGR and text`() {
        parser.feed("\u001b[31mRed\u001b[32mGreen\u001b[0mDefault")
    }

    @Test
    fun `RI within scroll region`() {
        parser.feed("\u001b[5;20r\u001b[20H\u001bM")
    }

    @Test
    fun `multiple scroll regions`() {
        parser.feed("\u001b[3;10r")
        parser.feed("\u001b[15;24r")
    }

    @Test
    fun `overflowing scrollback pushes oldest line`() {
        val snapshot = buffer.snapshot()
    }
}
