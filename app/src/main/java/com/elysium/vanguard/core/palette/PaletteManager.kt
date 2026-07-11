package com.elysium.vanguard.core.palette

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PHASE 10.8 — Runtime holder for the active [ColorPalette].
 *
 * The manager is the single source of truth for "what palette is the
 * app showing right now". UI subscribes to [current] (a [StateFlow])
 * and recomposes whenever the palette changes. Persistence is
 * delegated to [PaletteStore]; the manager keeps an in-memory
 * MutableStateFlow for synchronous reads and an async writer for
 * the SharedPreferences round-trip.
 *
 * Threading: all writes are safe to call from any thread. The
 * StateFlow update is synchronous; the SharedPreferences write is
 * dispatched to [scope] (a SupervisorJob on Dispatchers.IO) so the
 * caller never blocks.
 *
 * The manager is a Hilt singleton — there's exactly one instance for
 * the whole process, which is what we want for a global theme.
 */
@Singleton
class PaletteManager @Inject constructor(
    private val store: PaletteStore
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _current: MutableStateFlow<ColorPalette> =
        MutableStateFlow(store.loadCurrent())

    /** The currently active palette. Read-only [StateFlow]. */
    val current: StateFlow<ColorPalette> = _current.asStateFlow()

    /** Synchronous accessor — useful in non-Compose contexts. */
    val currentPalette: ColorPalette
        get() = _current.value

    /**
     * Replace the current palette. The change is visible to all
     * subscribers immediately; the persistence write happens in
     * the background.
     */
    fun setPalette(palette: ColorPalette) {
        _current.value = palette
        scope.launch { store.saveCurrent(palette) }
    }

    /**
     * Apply a built-in preset by id. Equivalent to
     * [setPalette] with [PalettePresets.byId].
     */
    fun applyPreset(presetId: String) {
        setPalette(PalettePresets.byId(presetId))
    }

    /**
     * Apply a user-saved custom palette by id. If no saved palette
     * matches, the current palette is left unchanged.
     */
    fun applyCustom(paletteId: String) {
        val saved = store.loadSaved().firstOrNull { it.id == paletteId }
        if (saved != null) {
            setPalette(saved.copy(isBuiltIn = false))
        }
    }

    /**
     * Edit the current palette: update one slot's base color.
     * The slot's derived colors (glow, metallic stops, diffusion)
     * are auto-recomputed via [ColorSlot.withBase].
     */
    fun updateSlotBase(slotName: String, base: androidx.compose.ui.graphics.Color) {
        val updated = _current.value.withSlotBase(slotName, base)
        if (updated !== _current.value) {
            _current.value = updated
            scope.launch { store.saveCurrent(updated) }
        }
    }

    /** Edit the current palette: change one slot's style. */
    fun updateSlotStyle(slotName: String, style: SlotStyle) {
        val updated = _current.value.withSlotStyle(slotName, style)
        if (updated !== _current.value) {
            _current.value = updated
            scope.launch { store.saveCurrent(updated) }
        }
    }

    /** Edit the current palette: change one slot's intensity. */
    fun updateSlotIntensity(slotName: String, intensity: Float) {
        val updated = _current.value.withSlotIntensity(slotName, intensity)
        if (updated !== _current.value) {
            _current.value = updated
            scope.launch { store.saveCurrent(updated) }
        }
    }

    /**
     * Save the current palette as a custom named palette. The
     * palette's [ColorPalette.id] is the name slug (lowercased,
     * spaces → underscores) so a user can re-load it later.
     */
    fun saveCurrentAs(name: String) {
        val slug = name.lowercase().trim().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
        val finalId = if (slug.isEmpty()) "custom_${System.currentTimeMillis()}" else slug
        val toSave = _current.value.copy(id = finalId, name = name, isBuiltIn = false)
        scope.launch { store.saveCustom(toSave) }
    }

    /**
     * Reset to the default palette (TITAN_DEFAULT). Discards any
     * customizations.
     */
    fun resetToDefault() {
        setPalette(PalettePresets.Default)
        scope.launch { store.resetCurrent() }
    }

    /**
     * Force a re-read of the persisted current palette. Useful on
     * the rare case where the preferences file was modified
     * out-of-band (e.g. by a backup restore).
     */
    fun reload() {
        _current.value = store.loadCurrent()
    }
}
