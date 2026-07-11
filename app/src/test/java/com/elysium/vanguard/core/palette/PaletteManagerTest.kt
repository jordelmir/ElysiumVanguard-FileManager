package com.elysium.vanguard.core.palette

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PHASE 10.8 — Unit tests for [PaletteManager].
 *
 * The manager wraps a [PaletteStore] and exposes a [StateFlow]
 * of the active palette. Tests use the [InMemorySharedPreferences]
 * fake so the manager can be wired up without Hilt or Android.
 *
 * The state-flow contract: every mutator that actually changes
 * the palette must update [PaletteManager.currentPalette]
 * synchronously, so the UI sees the change before the IO write
 * completes.
 */
class PaletteManagerTest {

    @Test
    fun `initial palette is loaded from the store`() {
        val store = PaletteStore(InMemorySharedPreferences())
        store.saveCurrent(PalettePresets.GOLD_METALLIC)
        val mgr = PaletteManager(store)
        assertEquals(PalettePresets.GOLD_METALLIC, mgr.currentPalette)
    }

    @Test
    fun `setPalette updates current synchronously`() {
        val mgr = newManager()
        mgr.setPalette(PalettePresets.OLED_BLACK)
        assertEquals(PalettePresets.OLED_BLACK, mgr.currentPalette)
    }

    @Test
    fun `applyPreset updates current to the named preset`() {
        val mgr = newManager()
        mgr.applyPreset("phosphor_green")
        assertEquals(PalettePresets.PHOSPHOR_GREEN, mgr.currentPalette)
    }

    @Test
    fun `applyPreset with unknown id is a no-op`() {
        val mgr = newManager()
        mgr.setPalette(PalettePresets.TITAN_DEFAULT)
        mgr.applyPreset("does_not_exist")
        assertEquals(PalettePresets.TITAN_DEFAULT, mgr.currentPalette)
    }

    @Test
    fun `updateSlotBase recomputes derived colors and updates state`() {
        val mgr = newManager()
        mgr.updateSlotBase("PRIMARY", Color.Cyan)
        val updated = mgr.currentPalette
        assertEquals(Color.Cyan, updated.primary.base)
        // Glow follows base.
        assertEquals(Color.Cyan, updated.primary.glow)
        // Style and intensity preserved.
        assertEquals(PalettePresets.TITAN_DEFAULT.primary.style, updated.primary.style)
        // Other slots untouched.
        assertEquals(PalettePresets.TITAN_DEFAULT.secondary, updated.secondary)
    }

    @Test
    fun `updateSlotStyle changes style and preserves base`() {
        val mgr = newManager()
        mgr.updateSlotStyle("PRIMARY", SlotStyle.METALLIC)
        val updated = mgr.currentPalette
        assertEquals(SlotStyle.METALLIC, updated.primary.style)
        assertEquals(PalettePresets.TITAN_DEFAULT.primary.base, updated.primary.base)
    }

    @Test
    fun `updateSlotIntensity clamps out-of-range values`() {
        val mgr = newManager()
        mgr.updateSlotIntensity("PRIMARY", 5f)
        assertEquals(2f, mgr.currentPalette.primary.intensity, 0.001f)
        mgr.updateSlotIntensity("PRIMARY", -1f)
        assertEquals(0f, mgr.currentPalette.primary.intensity, 0.001f)
    }

    @Test
    fun `resetToDefault returns the manager to TITAN_DEFAULT`() {
        val mgr = newManager()
        mgr.setPalette(PalettePresets.CYBER_MAGENTA)
        mgr.resetToDefault()
        assertEquals(PalettePresets.Default, mgr.currentPalette)
    }

    @Test
    fun `stateflow value tracks currentPalette`() {
        val mgr = newManager()
        mgr.setPalette(PalettePresets.PHOSPHOR_GREEN)
        assertEquals(PalettePresets.PHOSPHOR_GREEN, mgr.current.value)
    }

    // ── helper ────────────────────────────────────────────────

    private fun newManager(): PaletteManager {
        return PaletteManager(PaletteStore(InMemorySharedPreferences()))
    }
}
