package com.elysium.vanguard.core.palette

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * PHASE 10.8 — Unit tests for [ColorSlot] and [ColorPalette].
 *
 * Focus: data-class semantics, the [ColorSlot.withBase] helper,
 * the [ColorPalette.withSlot*] family of mutators, and the slot
 * name list. We don't test rendering here — that lives in
 * `SlotRenderersTest`.
 */
class ColorSlotTest {

    @Test
    fun `slot defaults preserve the base color as glow`() {
        val slot = ColorSlot(base = Color(0xFF112233))
        assertEquals(Color(0xFF112233), slot.glow)
    }

    @Test
    fun `slot rejects intensity out of range`() {
        try {
            ColorSlot(base = Color.Black, intensity = 2.5f)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("intensity"))
        }
        try {
            ColorSlot(base = Color.Black, intensity = -0.1f)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("intensity"))
        }
    }

    @Test
    fun `withBase recomputes derived colors from new base`() {
        val slot = ColorSlot(
            base = Color.Red,
            glow = Color.Red,
            metallicStart = Color.Red.copy(alpha = 0.3f),
            metallicEnd = Color.Red,
            diffused = Color.Red.copy(alpha = 0.2f),
            style = SlotStyle.NEON,
            intensity = 0.7f
        )
        val newSlot = slot.withBase(Color.Blue)
        assertEquals(Color.Blue, newSlot.base)
        assertEquals(Color.Blue, newSlot.glow)
        // metallicStart should keep the alpha fraction of the
        // new base, not the old one. Note: Compose's Color stores
        // alpha as a U8 (0..255), so the round-tripped alpha is
        // 77/255 ≈ 0.30196, not exactly 0.3. Use a tolerance.
        assertEquals(0.3f, newSlot.metallicStart.alpha, 0.01f)
        // style and intensity must survive the swap.
        assertEquals(SlotStyle.NEON, newSlot.style)
        assertEquals(0.7f, newSlot.intensity, 0.001f)
    }

    @Test
    fun `withBase returns a different instance even when base is unchanged`() {
        val slot = ColorSlot(base = Color.Green)
        val same = slot.withBase(Color.Green)
        // Not the same reference (data class copy) but equal in
        // value.
        assertNotSame(slot, same)
        assertEquals(slot, same)
    }

    @Test
    fun `palette withSlot updates only the named slot`() {
        val palette = samplePalette()
        val updated = palette.withSlot("PRIMARY", ColorSlot(base = Color.Yellow))
        assertEquals(Color.Yellow, updated.primary.base)
        // Other slots untouched.
        assertEquals(palette.secondary, updated.secondary)
        assertEquals(palette.tertiary, updated.tertiary)
        assertEquals(palette.quaternary, updated.quaternary)
    }

    @Test
    fun `palette withSlot is case-insensitive on slot name`() {
        val palette = samplePalette()
        val updated = palette.withSlot("secondary", ColorSlot(base = Color.Magenta))
        assertEquals(Color.Magenta, updated.secondary.base)
    }

    @Test
    fun `palette withSlot returns same palette on unknown slot name`() {
        val palette = samplePalette()
        val updated = palette.withSlot("NOT_A_SLOT", ColorSlot(base = Color.Yellow))
        // Data class equals: structurally identical because the
        // only field that changed would be... nothing.
        assertEquals(palette, updated)
    }

    @Test
    fun `palette withSlotBase recomputes derived colors of that slot`() {
        val palette = samplePalette()
        val updated = palette.withSlotBase("PRIMARY", Color.Cyan)
        assertEquals(Color.Cyan, updated.primary.base)
        assertEquals(Color.Cyan, updated.primary.glow)
        // secondary is still red — we didn't touch it.
        assertEquals(Color.Red, updated.secondary.base)
    }

    @Test
    fun `palette withSlotStyle changes only style, preserves base`() {
        val palette = samplePalette()
        val updated = palette.withSlotStyle("PRIMARY", SlotStyle.METALLIC)
        assertEquals(SlotStyle.METALLIC, updated.primary.style)
        assertEquals(palette.primary.base, updated.primary.base)
    }

    @Test
    fun `palette withSlotIntensity clamps out-of-range intensity`() {
        val palette = samplePalette()
        val updated = palette.withSlotIntensity("PRIMARY", 5f)
        assertEquals(2f, updated.primary.intensity, 0.001f)
        val updatedLow = palette.withSlotIntensity("PRIMARY", -1f)
        assertEquals(0f, updatedLow.primary.intensity, 0.001f)
    }

    @Test
    fun `palette slot names list is stable and contains expected names`() {
        val names = ColorPalette.SLOT_NAMES
        assertEquals(5, names.size)
        assertTrue("PRIMARY" in names)
        assertTrue("SECONDARY" in names)
        assertTrue("TERTIARY" in names)
        assertTrue("QUATERNARY" in names)
        assertTrue("ACCENT" in names)
    }

    @Test
    fun `two palettes with same fields are equal`() {
        val a = samplePalette()
        val b = samplePalette()
        assertEquals(a, b)
        // data class hashCode: equal objects have equal hashCodes.
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `palettes with different slots are not equal`() {
        val a = samplePalette()
        val b = a.copy(secondary = ColorSlot(base = Color.White))
        assertNotEquals(a, b)
    }

    // ── helper ────────────────────────────────────────────────

    private fun samplePalette(): ColorPalette = ColorPalette(
        id = "test",
        name = "Test",
        primary = ColorSlot(base = Color.Blue),
        secondary = ColorSlot(base = Color.Red),
        tertiary = ColorSlot(base = Color.Green),
        quaternary = ColorSlot(base = Color.Yellow),
        accent = ColorSlot(base = Color.Blue)
    )
}
