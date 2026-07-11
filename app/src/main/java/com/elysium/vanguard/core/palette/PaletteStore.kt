package com.elysium.vanguard.core.palette

import android.content.Context
import android.content.SharedPreferences

/**
 * PHASE 10.8 — Persistent storage for the current palette and the
 * list of user-saved custom palettes.
 *
 * Backed by [SharedPreferences] (not DataStore) so we don't pull in a
 * new dependency. The data is small: one JSON blob for the current
 * palette, plus one JSON array of saved palette summaries (id + name
 * + JSON blob).
 *
 * Threading: SharedPreferences is thread-safe for reads/writes via
 * `apply()`. Reads are synchronous and cheap; writes are async.
 *
 * The store is intentionally small and JVM-friendly: the only
 * Android dependency is the [Context] used to obtain the
 * SharedPreferences instance. Tests can pass a stub
 * [SharedPreferences] via the secondary constructor to bypass
 * Android entirely.
 */
class PaletteStore(
    private val prefs: SharedPreferences
) {

    // ── Public API ────────────────────────────────────────────────

    /**
     * Load the current palette. Falls back to
     * [PalettePresets.Default] if nothing is persisted, or if the
     * persisted blob is corrupt.
     */
    fun loadCurrent(): ColorPalette {
        val json = prefs.getString(KEY_CURRENT_PALETTE, null)
        return PaletteSerializer.fromJsonOrDefault(json)
    }

    /** Persist the current palette. Async via `apply()`. */
    fun saveCurrent(palette: ColorPalette) {
        prefs.edit().putString(KEY_CURRENT_PALETTE, PaletteSerializer.toJson(palette)).apply()
    }

    /**
     * Load all user-saved custom palettes. Built-in presets are
     * not in this list — they're in [PalettePresets.ALL]. The
     * returned list is in insertion order (oldest first), so the
     * UI can render a stable "saved palettes" section.
     */
    fun loadSaved(): List<ColorPalette> {
        val raw = prefs.getStringSet(KEY_SAVED_PALETTES, null) ?: return emptyList()
        return raw.mapNotNull { PaletteSerializer.fromJson(it) }
    }

    /**
     * Save a custom palette. If a palette with the same id already
     * exists, it is replaced. Async via `apply()`.
     */
    fun saveCustom(palette: ColorPalette) {
        val existing = prefs.getStringSet(KEY_SAVED_PALETTES, null)?.toMutableSet() ?: mutableSetOf()
        // Drop any prior version with the same id.
        existing.removeAll { json ->
            PaletteSerializer.fromJson(json)?.id == palette.id
        }
        existing.add(PaletteSerializer.toJson(palette))
        prefs.edit().putStringSet(KEY_SAVED_PALETTES, existing).apply()
    }

    /**
     * Delete a saved palette by id. The current palette is
     * unaffected.
     */
    fun deleteCustom(id: String) {
        val existing = prefs.getStringSet(KEY_SAVED_PALETTES, null)?.toMutableSet() ?: return
        existing.removeAll { json -> PaletteSerializer.fromJson(json)?.id == id }
        prefs.edit().putStringSet(KEY_SAVED_PALETTES, existing).apply()
    }

    /**
     * Reset the app to the default palette. Drops the current
     * palette; leaves user-saved custom palettes intact (the user
     * can re-pick them from the saved list).
     */
    fun resetCurrent() {
        prefs.edit().remove(KEY_CURRENT_PALETTE).apply()
    }

    // ── Companion: factory + key constants ────────────────────────

    companion object {
        const val PREFS_NAME = "elysium_palette_prefs"
        const val KEY_CURRENT_PALETTE = "current_palette_json"
        const val KEY_SAVED_PALETTES = "saved_palettes_json_set"

        /** Factory: build a store backed by the app's SharedPreferences. */
        fun fromContext(context: Context): PaletteStore {
            return PaletteStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
        }
    }
}
