package com.elysium.vanguard.core.palette

import androidx.compose.ui.graphics.Color
import com.elysium.vanguard.features.customization.colorFromHsv
import com.elysium.vanguard.features.customization.hsvFromColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 10.8 — Unit tests for the HSV <-> RGB math used by the
 * color picker. Pure JVM, no Compose, no Android.
 *
 * The contract:
 *  - HSV -> RGB -> HSV is identity (modulo rounding).
 *  - Black, white, and pure colors round-trip cleanly.
 *  - Hue wrapping (e.g. 360° == 0°) is handled.
 */
class HsvColorMathTest {

    @Test
    fun `pure red is hue 0 sat 1 val 1`() {
        val out = FloatArray(3)
        hsvFromColor(Color.Red, out)
        // 0° or 360° — both acceptable; we treat them as equal.
        val h = if (out[0] == 360f) 0f else out[0]
        assertEquals(0f, h, 0.5f)
        assertEquals(1f, out[1], 0.001f)
        assertEquals(1f, out[2], 0.001f)
    }

    @Test
    fun `pure green is hue 120`() {
        val out = FloatArray(3)
        hsvFromColor(Color.Green, out)
        assertEquals(120f, out[0], 0.5f)
    }

    @Test
    fun `pure blue is hue 240`() {
        val out = FloatArray(3)
        hsvFromColor(Color.Blue, out)
        assertEquals(240f, out[0], 0.5f)
    }

    @Test
    fun `black has hue 0 sat 0 val 0`() {
        val out = FloatArray(3)
        hsvFromColor(Color.Black, out)
        assertEquals(0f, out[1], 0.001f)
        assertEquals(0f, out[2], 0.001f)
    }

    @Test
    fun `white has sat 0 val 1`() {
        val out = FloatArray(3)
        hsvFromColor(Color.White, out)
        assertEquals(0f, out[1], 0.001f)
        assertEquals(1f, out[2], 0.001f)
    }

    @Test
    fun `colorFromHsv red is Color_red`() {
        val c = colorFromHsv(0f, 1f, 1f)
        assertEquals(Color.Red.red, c.red, 0.01f)
        assertEquals(Color.Red.green, c.green, 0.01f)
        assertEquals(Color.Red.blue, c.blue, 0.01f)
    }

    @Test
    fun `colorFromHsv green is Color_green`() {
        val c = colorFromHsv(120f, 1f, 1f)
        assertEquals(Color.Green.red, c.red, 0.01f)
        assertEquals(Color.Green.green, c.green, 0.01f)
        assertEquals(Color.Green.blue, c.blue, 0.01f)
    }

    @Test
    fun `colorFromHsv blue is Color_blue`() {
        val c = colorFromHsv(240f, 1f, 1f)
        assertEquals(Color.Blue.red, c.red, 0.01f)
        assertEquals(Color.Blue.green, c.green, 0.01f)
        assertEquals(Color.Blue.blue, c.blue, 0.01f)
    }

    @Test
    fun `hsv round-trip preserves all three channels`() {
        val cases = listOf(
            Triple(0f, 0f, 0f),    // black
            Triple(0f, 0f, 1f),    // white
            Triple(0f, 1f, 1f),    // red
            Triple(120f, 1f, 1f),  // green
            Triple(240f, 1f, 1f),  // blue
            Triple(45f, 0.5f, 0.5f),
            Triple(180f, 0.7f, 0.3f),
            Triple(300f, 0.2f, 0.9f)
        )
        for ((h, s, v) in cases) {
            val c = colorFromHsv(h, s, v)
            val out = FloatArray(3)
            hsvFromColor(c, out)
            // Hue is circular — 359.9 == 0.1 modulo 360.
            val dH = minOf(
                kotlin.math.abs(out[0] - h),
                kotlin.math.abs(out[0] - h + 360f),
                kotlin.math.abs(out[0] - h - 360f)
            )
            assertTrue("hue drift ${dH} for ($h, $s, $v)", dH < 1.0f)
            assertEquals("s drift for ($h, $s, $v)", s, out[1], 0.01f)
            assertEquals("v drift for ($h, $s, $v)", v, out[2], 0.01f)
        }
    }
}
