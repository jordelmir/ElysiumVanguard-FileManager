package com.elysium.vanguard.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UniformNeonSurfaceTest {

    @Test
    fun `colored surface is opaque and derived from its module color`() {
        val result = uniformNeonSurfaceColor(Color(0f, 1f, 0.5f, 1f), intensity = 0.25f)

        // Compose stores sRGB channels with 8-bit quantization.
        assertEquals(1f, result.alpha, 0.005f)
        assertEquals(0f, result.red, 0.005f)
        assertEquals(0.25f, result.green, 0.005f)
        assertEquals(0.125f, result.blue, 0.005f)
    }

    @Test
    fun `zero intensity remains transparent for border only containers`() {
        val result = uniformNeonSurfaceColor(Color.Cyan, intensity = 0f)

        assertTrue(result == Color.Transparent)
    }
}
