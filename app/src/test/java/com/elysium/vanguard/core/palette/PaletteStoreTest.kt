package com.elysium.vanguard.core.palette

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PHASE 10.8 — Unit tests for [PaletteStore].
 *
 * Uses an in-memory [SharedPreferences] fake
 * ([InMemorySharedPreferences]) so the tests don't need a real
 * Android Context.
 */
class PaletteStoreTest {

    @Test
    fun `loadCurrent returns default when nothing is persisted`() {
        val store = PaletteStore(InMemorySharedPreferences())
        val loaded = store.loadCurrent()
        assertEquals(PalettePresets.Default, loaded)
    }

    @Test
    fun `saveCurrent then loadCurrent returns the same palette`() {
        val store = PaletteStore(InMemorySharedPreferences())
        val custom = PalettePresets.CYBER_MAGENTA.copy(id = "custom_x", name = "X")
        store.saveCurrent(custom)
        val loaded = store.loadCurrent()
        assertEquals(custom, loaded)
    }

    @Test
    fun `saveCustom then loadSaved returns the palette`() {
        val store = PaletteStore(InMemorySharedPreferences())
        val custom = PalettePresets.PHOSPHOR_GREEN.copy(id = "my_phosphor", name = "Phos")
        store.saveCustom(custom)
        val saved = store.loadSaved()
        assertEquals(1, saved.size)
        assertEquals("my_phosphor", saved[0].id)
    }

    @Test
    fun `saveCustom with same id replaces the prior entry`() {
        val store = PaletteStore(InMemorySharedPreferences())
        val first = PalettePresets.PHOSPHOR_GREEN.copy(id = "phosphor_v1", name = "v1")
        val second = PalettePresets.PHOSPHOR_GREEN.copy(id = "phosphor_v1", name = "v2")
        store.saveCustom(first)
        store.saveCustom(second)
        val saved = store.loadSaved()
        assertEquals(1, saved.size)
        assertEquals("v2", saved[0].name)
    }

    @Test
    fun `deleteCustom removes only the matching id`() {
        val store = PaletteStore(InMemorySharedPreferences())
        store.saveCustom(PalettePresets.PHOSPHOR_GREEN.copy(id = "a", name = "A"))
        store.saveCustom(PalettePresets.GOLD_METALLIC.copy(id = "b", name = "B"))
        store.deleteCustom("a")
        val saved = store.loadSaved()
        assertEquals(1, saved.size)
        assertEquals("b", saved[0].id)
    }

    @Test
    fun `resetCurrent drops the persisted current but keeps saved palettes`() {
        val store = PaletteStore(InMemorySharedPreferences())
        store.saveCurrent(PalettePresets.OLED_BLACK)
        store.saveCustom(PalettePresets.PHOSPHOR_GREEN.copy(id = "p", name = "P"))
        store.resetCurrent()
        // Current reverts to default.
        assertEquals(PalettePresets.Default, store.loadCurrent())
        // Saved palette is intact.
        assertEquals(1, store.loadSaved().size)
    }

    @Test
    fun `corrupt current blob falls back to default on load`() {
        val prefs = InMemorySharedPreferences().also {
            it.edit().putString(PaletteStore.KEY_CURRENT_PALETTE, "garbage{not json").apply()
        }
        val store = PaletteStore(prefs)
        assertEquals(PalettePresets.Default, store.loadCurrent())
    }
}
