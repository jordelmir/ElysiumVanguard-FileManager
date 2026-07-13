package com.elysium.vanguard.core.runtime.distros.gui.rfb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RfbViewportTest {

    @Test
    fun `letterboxes a widescreen Linux framebuffer on a square surface`() {
        val viewport = RfbViewport(
            framebufferWidth = 200,
            framebufferHeight = 100,
            surfaceWidth = 100,
            surfaceHeight = 100
        )

        assertEquals(100f, viewport.drawWidth)
        assertEquals(50f, viewport.drawHeight)
        assertEquals(25f, viewport.offsetY)
        assertEquals(RfbPointer(100, 50), viewport.map(50f, 50f))
        assertNull(viewport.map(50f, 10f))
    }

    @Test
    fun `maps every edge inside a foldable sized surface without overflow`() {
        val viewport = RfbViewport(
            framebufferWidth = 1920,
            framebufferHeight = 1080,
            surfaceWidth = 2208,
            surfaceHeight = 1840
        )

        assertEquals(RfbPointer(0, 0), viewport.map(viewport.offsetX, viewport.offsetY))
        assertEquals(
            RfbPointer(1919, 1079),
            viewport.map(viewport.offsetX + viewport.drawWidth - 0.01f, viewport.offsetY + viewport.drawHeight - 0.01f)
        )
    }
}
