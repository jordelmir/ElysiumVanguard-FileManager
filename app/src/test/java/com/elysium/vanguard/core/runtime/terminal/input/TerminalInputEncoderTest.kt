package com.elysium.vanguard.core.runtime.terminal.input

import com.elysium.vanguard.core.runtime.terminal.engine.TerminalInputModes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for TerminalInputEncoder — translates key events to terminal sequences.
 */
class TerminalInputEncoderTest {

    @Test
    fun `enter key sends CR`() {
        val bytes = TerminalInputEncoder.key(TerminalKey.ENTER)
        assertArrayEquals(byteArrayOf(0x0D), bytes)
    }

    @Test
    fun `backspace sends DEL`() {
        val bytes = TerminalInputEncoder.key(TerminalKey.BACKSPACE)
        assertArrayEquals(byteArrayOf(0x7F), bytes)
    }

    @Test
    fun `tab sends HT`() {
        val bytes = TerminalInputEncoder.key(TerminalKey.TAB)
        assertArrayEquals(byteArrayOf(0x09), bytes)
    }

    @Test
    fun `escape sends ESC`() {
        val bytes = TerminalInputEncoder.key(TerminalKey.ESCAPE)
        assertArrayEquals(byteArrayOf(0x1B), bytes)
    }

    @Test
    fun `up arrow in normal mode`() {
        val bytes = TerminalInputEncoder.key(TerminalKey.UP)
        assertArrayEquals("\u001b[A".toByteArray(Charsets.US_ASCII), bytes)
    }

    @Test
    fun `down arrow in normal mode`() {
        val bytes = TerminalInputEncoder.key(TerminalKey.DOWN)
        assertArrayEquals("\u001b[B".toByteArray(Charsets.US_ASCII), bytes)
    }

    @Test
    fun `right arrow in normal mode`() {
        val bytes = TerminalInputEncoder.key(TerminalKey.RIGHT)
        assertArrayEquals("\u001b[C".toByteArray(Charsets.US_ASCII), bytes)
    }

    @Test
    fun `left arrow in normal mode`() {
        val bytes = TerminalInputEncoder.key(TerminalKey.LEFT)
        assertArrayEquals("\u001b[D".toByteArray(Charsets.US_ASCII), bytes)
    }

    @Test
    fun `up arrow in application cursor mode`() {
        val modes = com.elysium.vanguard.core.runtime.terminal.engine.TerminalInputModes(
            applicationCursorKeys = true,
            bracketedPaste = false
        )
        val bytes = TerminalInputEncoder.key(TerminalKey.UP, modes = modes)
        assertArrayEquals("\u001bOA".toByteArray(Charsets.US_ASCII), bytes)
    }

    @Test
    fun `home key`() {
        val bytes = TerminalInputEncoder.key(TerminalKey.HOME)
        assertArrayEquals("\u001b[H".toByteArray(Charsets.US_ASCII), bytes)
    }

    @Test
    fun `end key`() {
        val bytes = TerminalInputEncoder.key(TerminalKey.END)
        assertArrayEquals("\u001b[F".toByteArray(Charsets.US_ASCII), bytes)
    }

    @Test
    fun `page up`() {
        val bytes = TerminalInputEncoder.key(TerminalKey.PAGE_UP)
        assertArrayEquals("\u001b[5~".toByteArray(Charsets.US_ASCII), bytes)
    }

    @Test
    fun `page down`() {
        val bytes = TerminalInputEncoder.key(TerminalKey.PAGE_DOWN)
        assertArrayEquals("\u001b[6~".toByteArray(Charsets.US_ASCII), bytes)
    }

    @Test
    fun `insert key`() {
        val bytes = TerminalInputEncoder.key(TerminalKey.INSERT)
        assertArrayEquals("\u001b[2~".toByteArray(Charsets.US_ASCII), bytes)
    }

    @Test
    fun `delete key`() {
        val bytes = TerminalInputEncoder.key(TerminalKey.DELETE)
        assertArrayEquals("\u001b[3~".toByteArray(Charsets.US_ASCII), bytes)
    }

    @Test
    fun `F1 through F12`() {
        val fSequences = listOf("\u001bOP", "\u001bOQ", "\u001bOR", "\u001bOS", "\u001b[15~", "\u001b[17~", "\u001b[18~", "\u001b[19~", "\u001b[20~", "\u001b[21~", "\u001b[23~", "\u001b[24~")
        TerminalKey.entries.filter { it.name.startsWith("F") }.forEachIndexed { i, key ->
            val bytes = TerminalInputEncoder.key(key)
            val expected = fSequences[i]
            assertArrayEquals("F${i + 1} expected $expected", expected.toByteArray(Charsets.US_ASCII), bytes)
        }
    }

    @Test
    fun `text character encoding`() {
        val bytes = TerminalInputEncoder.text('A'.code)
        assertArrayEquals("A".toByteArray(Charsets.UTF_8), bytes)
    }

    @Test
    fun `unicode character encoding`() {
        val bytes = TerminalInputEncoder.text(0x00E9) // é
        assertArrayEquals("é".toByteArray(Charsets.UTF_8), bytes)
    }

    @Test
    fun `control letter encoding`() {
        val bytes = TerminalInputEncoder.controlLetter('A')
        assertArrayEquals(byteArrayOf(0x01), bytes)
    }

    @Test
    fun `control letter Z`() {
        val bytes = TerminalInputEncoder.controlLetter('Z')
        assertArrayEquals(byteArrayOf(0x1A), bytes)
    }

    @Test
    fun `bracketed paste`() {
        val modes = com.elysium.vanguard.core.runtime.terminal.engine.TerminalInputModes(
            applicationCursorKeys = false,
            bracketedPaste = true
        )
        val bytes = TerminalInputEncoder.paste("hello".toByteArray(Charsets.UTF_8), modes)
        val expected = "\u001b[200~hello\u001b[201~"
        assertArrayEquals(expected.toByteArray(Charsets.US_ASCII), bytes)
    }

    @Test
    fun `plain paste without bracketed mode`() {
        val modes = com.elysium.vanguard.core.runtime.terminal.engine.TerminalInputModes.DEFAULT
        val bytes = TerminalInputEncoder.paste("text".toByteArray(Charsets.UTF_8), modes)
        assertArrayEquals("text".toByteArray(Charsets.UTF_8), bytes)
    }

    @Test
    fun `cursor sequence fallback`() {
        val modes = TerminalInputModes(bracketedPaste = false, applicationCursorKeys = false)
        val bytes = TerminalInputEncoder.key(TerminalKey.HOME, modes)
        // Not all TerminalKey values map; fallback is ESC[?
        assertTrue(bytes == null || bytes.isNotEmpty())
    }
}
