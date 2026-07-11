package com.elysium.vanguard.features.customization

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.palette.ColorPalette
import com.elysium.vanguard.core.palette.PaletteManager
import com.elysium.vanguard.core.palette.PalettePresets
import com.elysium.vanguard.core.palette.PaletteStore
import com.elysium.vanguard.core.palette.SlotStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * PHASE 10.8 — State holder for the [ColorCustomizationScreen].
 *
 * The screen needs three things from the palette subsystem:
 *
 *  1. The currently-active palette (so the editor shows it).
 *  2. The list of available presets (so the user can switch
 *     with one tap).
 *  3. The list of user-saved custom palettes (so the user can
 *     reload one).
 *
 * The ViewModel exposes all three as Compose [StateFlow]s and
 * provides mutator methods that delegate to [PaletteManager] /
 * [PaletteStore]. It also tracks a "draft" name for the save
 * dialog so the user can name the palette before saving.
 */
@HiltViewModel
class ColorCustomizationViewModel @Inject constructor(
    private val manager: PaletteManager,
    private val store: PaletteStore
) : ViewModel() {

    /** Active palette, updated live. */
    val palette: StateFlow<ColorPalette> = manager.current

    /** Built-in presets. */
    val presets: StateFlow<List<ColorPalette>> = MutableStateFlow(PalettePresets.ALL).asStateFlow()

    /** User-saved palettes; re-read on demand. */
    private val _saved = MutableStateFlow<List<ColorPalette>>(emptyList())
    val saved: StateFlow<List<ColorPalette>> = _saved.asStateFlow()

    /** Name to use when saving the current palette. */
    private val _draftName = MutableStateFlow("My Palette")
    val draftName: StateFlow<String> = _draftName.asStateFlow()

    init {
        refreshSaved()
    }

    /** Re-read the list of saved custom palettes from the store. */
    fun refreshSaved() {
        _saved.value = store.loadSaved()
    }

    fun setDraftName(name: String) {
        _draftName.value = name
    }

    fun applyPreset(presetId: String) {
        manager.applyPreset(presetId)
    }

    fun applySaved(id: String) {
        manager.applyCustom(id)
    }

    fun updateSlotBase(slotName: String, base: Color) {
        manager.updateSlotBase(slotName, base)
    }

    fun updateSlotStyle(slotName: String, style: SlotStyle) {
        manager.updateSlotStyle(slotName, style)
    }

    fun updateSlotIntensity(slotName: String, intensity: Float) {
        manager.updateSlotIntensity(slotName, intensity)
    }

    fun resetToDefault() {
        manager.resetToDefault()
    }

    fun saveCurrent() {
        val name = _draftName.value.ifBlank { "My Palette" }
        manager.saveCurrentAs(name)
        // Re-read the saved list so the UI sees the new entry.
        viewModelScope.launch { refreshSaved() }
    }

    fun deleteSaved(id: String) {
        viewModelScope.launch {
            store.deleteCustom(id)
            refreshSaved()
        }
    }
}
