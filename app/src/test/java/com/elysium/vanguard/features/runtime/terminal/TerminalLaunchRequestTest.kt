package com.elysium.vanguard.features.runtime.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalLaunchRequestTest {

    @Test
    fun `round trip preserves desktop command punctuation`() {
        val command = "firefox --new-window 'https://example.test/?a=1&b=2'"
        assertEquals(command, TerminalLaunchRequest.decode(TerminalLaunchRequest.encode(command)))
    }

    @Test
    fun `invalid payloads are rejected instead of becoming shell input`() {
        assertNull(TerminalLaunchRequest.decode("%%%"))
        assertNull(TerminalLaunchRequest.decode(""))
    }

    @Test
    fun `terminal input ends with exactly one command delimiter`() {
        assertEquals("htop\n", TerminalLaunchRequest.asTerminalInput("htop"))
        assertEquals("htop\n", TerminalLaunchRequest.asTerminalInput("htop\n"))
    }
}
