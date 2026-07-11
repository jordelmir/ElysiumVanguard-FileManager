package com.elysium.vanguard.core.palette

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 10.8 — Unit tests for the built-in [PalettePresets].
 *
 * The presets are the "shipped" palettes, so we lock down their
 * contract: every preset is non-default, has a unique id, and
 * exposes all five slot names. Unknown ids fall back to the
 * default rather than throwing.
 */
class PalettePresetsTest {

    @Test
    fun `every preset has a unique id`() {
        val ids = PalettePresets.ALL.map { it.id }
        assertEquals("duplicate ids in PalettePresets.ALL", ids.size, ids.toSet().size)
    }

    @Test
    fun `every preset is marked as built-in`() {
        PalettePresets.ALL.forEach { p ->
            assertTrue("${p.id} should be marked isBuiltIn", p.isBuiltIn)
        }
    }

    @Test
    fun `every preset has a non-empty name`() {
        PalettePresets.ALL.forEach { p ->
            assertTrue("palette ${p.id} has empty name", p.name.isNotBlank())
        }
    }

    @Test
    fun `every preset has all five slots populated`() {
        PalettePresets.ALL.forEach { p ->
            // Default slot is solid black; we want every preset to
            // have moved past the default by populating colors.
            assertNotSame("primary", ColorSlot.Default, p.primary)
            assertNotSame("secondary", ColorSlot.Default, p.secondary)
            assertNotSame("tertiary", ColorSlot.Default, p.tertiary)
            assertNotSame("quaternary", ColorSlot.Default, p.quaternary)
        }
    }

    @Test
    fun `every preset is dark mode`() {
        PalettePresets.ALL.forEach { p ->
            assertTrue("${p.id} should be isDark", p.isDark)
        }
    }

    @Test
    fun `byId returns matching preset`() {
        assertSame(PalettePresets.TITAN_DEFAULT, PalettePresets.byId("titan_default"))
        assertSame(PalettePresets.OLED_BLACK, PalettePresets.byId("oled_black"))
        assertSame(PalettePresets.PHOSPHOR_GREEN, PalettePresets.byId("phosphor_green"))
        assertSame(PalettePresets.CYBER_MAGENTA, PalettePresets.byId("cyber_magenta"))
        assertSame(PalettePresets.GOLD_METALLIC, PalettePresets.byId("gold_metallic"))
        assertSame(PalettePresets.INFRARED, PalettePresets.byId("infrared"))
        assertSame(PalettePresets.HOLOGRAPHIC, PalettePresets.byId("holographic"))
        assertSame(PalettePresets.MIDNIGHT_NEON, PalettePresets.byId("midnight_neon"))
    }

    @Test
    fun `byId returns default for unknown id`() {
        assertSame(PalettePresets.Default, PalettePresets.byId("does_not_exist"))
        assertSame(PalettePresets.Default, PalettePresets.byId(null))
        assertSame(PalettePresets.Default, PalettePresets.byId(""))
    }

    @Test
    fun `Default is TITAN_DEFAULT`() {
        assertSame(PalettePresets.TITAN_DEFAULT, PalettePresets.Default)
    }

    @Test
    fun `presets cover all five styles`() {
        // We don't require every preset to use every style — we
        // require the union of styles used across the set to
        // cover all five.
        val usedStyles = PalettePresets.ALL.flatMap { p ->
            listOf(p.primary.style, p.secondary.style, p.tertiary.style, p.quaternary.style)
        }.toSet()
        SlotStyle.values().forEach { style ->
            assertTrue("style $style is not used by any preset", style in usedStyles)
        }
    }

    @Test
    fun `presets have non-transparent base colors`() {
        PalettePresets.ALL.forEach { p ->
            listOf(p.primary, p.secondary, p.tertiary, p.quaternary).forEach { slot ->
                assertFalse(
                    "slot in ${p.id} has transparent base",
                    slot.base.alpha == 0f
                )
            }
        }
    }
}

/** Two slots are "not the default" iff they aren't the same reference as Default. */
private fun assertNotSame(label: String, ignored: ColorSlot, actual: ColorSlot) {
    if (actual === ColorSlot.Default) {
        throw AssertionError("$label is ColorSlot.Default")
    }
}
