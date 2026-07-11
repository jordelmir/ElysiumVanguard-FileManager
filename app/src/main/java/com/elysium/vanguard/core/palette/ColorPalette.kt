package com.elysium.vanguard.core.palette

import androidx.compose.ui.graphics.Color

/**
 * PHASE 10.8 — A complete color palette for the Elysium Vanguard UI.
 *
 * A palette is the unit of customization the user saves and loads. It
 * has:
 *
 *  - A stable [id] used as the persistence key (never localized).
 *  - A human [name] shown in the picker (localizable).
 *  - Four "accent" slots: [primary], [secondary], [tertiary],
 *    [quaternary]. These are the slots the user is most likely to
 *    tweak per-section.
 *  - A [background] / [surface] / [onBackground] triplet that
 *    overrides the dark-mode defaults. OLED_BLACK, for instance, sets
 *    background to true 0xFF000000.
 *  - [isDark] — false means the surface/background tones flip to
 *    light, which inverts which color in [base] vs [glow] is the
 *    "ink" vs the "halo".
 *
 * Palettes are value types (data class) — equality is structural, so
 * they can be used as Map keys and diffed cheaply in recomposition.
 */
data class ColorPalette(
    val id: String,
    val name: String,
    val primary: ColorSlot,
    val secondary: ColorSlot,
    val tertiary: ColorSlot,
    val quaternary: ColorSlot,
    val accent: ColorSlot = primary,
    val background: Color = Color(0xFF000000),
    val surface: Color = Color(0xFF080808),
    val onBackground: Color = Color(0xFFE0E0E0),
    val onSurface: Color = Color(0xFFE0E0E0),
    val isDark: Boolean = true,
    val isBuiltIn: Boolean = false
) {
    /**
     * Returns a new palette with one slot swapped. Used by the
     * customization screen when the user edits a single slot without
     * touching the others.
     */
    fun withSlot(slotName: String, slot: ColorSlot): ColorPalette {
        return when (slotName.uppercase()) {
            "PRIMARY" -> copy(primary = slot)
            "SECONDARY" -> copy(secondary = slot)
            "TERTIARY" -> copy(tertiary = slot)
            "QUATERNARY" -> copy(quaternary = slot)
            "ACCENT" -> copy(accent = slot)
            else -> this
        }
    }

    /**
     * Returns a new palette with the [base] color of one slot
     * replaced. The slot's derived colors (glow, metallic stops,
     * diffusion) are auto-recomputed via
     * [ColorSlot.withBase] so the live preview stays in sync with
     * the picker.
     */
    fun withSlotBase(slotName: String, base: Color): ColorPalette {
        val current = when (slotName.uppercase()) {
            "PRIMARY" -> primary
            "SECONDARY" -> secondary
            "TERTIARY" -> tertiary
            "QUATERNARY" -> quaternary
            "ACCENT" -> accent
            else -> return this
        }
        return withSlot(slotName, current.withBase(base))
    }

    /**
     * Returns a new palette with one slot's style changed. The base
     * color is preserved.
     */
    fun withSlotStyle(slotName: String, style: SlotStyle): ColorPalette {
        val current = when (slotName.uppercase()) {
            "PRIMARY" -> primary
            "SECONDARY" -> secondary
            "TERTIARY" -> tertiary
            "QUATERNARY" -> quaternary
            "ACCENT" -> accent
            else -> return this
        }
        return withSlot(slotName, current.copy(style = style))
    }

    /**
     * Returns a new palette with one slot's intensity changed.
     */
    fun withSlotIntensity(slotName: String, intensity: Float): ColorPalette {
        val current = when (slotName.uppercase()) {
            "PRIMARY" -> primary
            "SECONDARY" -> secondary
            "TERTIARY" -> tertiary
            "QUATERNARY" -> quaternary
            "ACCENT" -> accent
            else -> return this
        }
        return withSlot(slotName, current.copy(intensity = intensity.coerceIn(0f, 2f)))
    }

    companion object {
        /**
         * The slot names that the customization screen exposes, in
         * their display order. Used to iterate when the screen first
         * renders, and by tests to assert the slot list is stable.
         */
        val SLOT_NAMES: List<String> = listOf("PRIMARY", "SECONDARY", "TERTIARY", "QUATERNARY", "ACCENT")
    }
}
