package com.elysium.vanguard.core.runtime.distros.gui.rfb

import org.junit.Assert.assertEquals
import org.junit.Test

class RfbImeComposerTest {

    @Test
    fun `replaces incremental composing text and does not duplicate its final commit`() {
        val events = mutableListOf<String>()
        val composer = RfbImeComposer(
            sendText = { events += "text:$it" },
            sendBackspace = { events += "backspace" }
        )

        composer.setComposingText("he")
        composer.setComposingText("hello")
        composer.commitText("hello")

        assertEquals(
            listOf("text:he", "backspace", "backspace", "text:hello"),
            events
        )
    }

    @Test
    fun `replaces composition when an IME commits an autocorrection`() {
        val events = mutableListOf<String>()
        val composer = RfbImeComposer(
            sendText = { events += "text:$it" },
            sendBackspace = { events += "backspace" }
        )

        composer.setComposingText("teh")
        composer.commitText("the")

        assertEquals(
            listOf("text:teh", "backspace", "backspace", "backspace", "text:the"),
            events
        )
    }
}
