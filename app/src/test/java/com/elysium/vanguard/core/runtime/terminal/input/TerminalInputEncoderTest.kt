package com.elysium.vanguard.core.runtime.terminal.input

import com.elysium.vanguard.core.runtime.terminal.engine.TerminalInputModes
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class TerminalInputEncoderTest {
    @Test
    fun `application cursor mode changes unmodified arrows to SS3`() {
        assertArrayEquals(
            "\u001bOA".toByteArray(),
            TerminalInputEncoder.key(TerminalKey.UP, TerminalInputModes(applicationCursorKeys = true))
        )
        assertArrayEquals(
            "\u001b[1;2A".toByteArray(),
            TerminalInputEncoder.key(TerminalKey.UP, TerminalInputModes(applicationCursorKeys = true), shift = true)
        )
    }

    @Test
    fun `function navigation and modified keys use xterm sequences`() {
        assertArrayEquals("\u001b[3~".toByteArray(), TerminalInputEncoder.key(TerminalKey.DELETE))
        assertArrayEquals("\u001b[5;5~".toByteArray(), TerminalInputEncoder.key(TerminalKey.PAGE_UP, ctrl = true))
        assertArrayEquals("\u001bOP".toByteArray(), TerminalInputEncoder.key(TerminalKey.F1))
        assertArrayEquals("\u001b[24~".toByteArray(), TerminalInputEncoder.key(TerminalKey.F12))
    }

    @Test
    fun `bracketed paste frames payload only when negotiated`() {
        val payload = "echo 'safe; text'".toByteArray()
        assertArrayEquals(payload, TerminalInputEncoder.paste(payload, TerminalInputModes.DEFAULT))
        assertArrayEquals(
            "\u001b[200~echo 'safe; text'\u001b[201~".toByteArray(),
            TerminalInputEncoder.paste(payload, TerminalInputModes(bracketedPaste = true))
        )
    }

    @Test
    fun `alt text and control letters preserve established terminal conventions`() {
        assertArrayEquals(byteArrayOf(0x1b, 0x03), TerminalInputEncoder.controlLetter('C', alt = true))
        assertArrayEquals("\u001bé".toByteArray(), TerminalInputEncoder.text('é'.code, alt = true))
    }
}
