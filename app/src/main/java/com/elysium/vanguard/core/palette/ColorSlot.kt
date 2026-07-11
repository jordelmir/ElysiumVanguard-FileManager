package com.elysium.vanguard.core.palette

import androidx.compose.ui.graphics.Color

/**
 * PHASE 10.8 — Color customization foundation.
 *
 * A [ColorSlot] is a single named "slot" in a palette (primary, secondary,
 * tertiary, quaternary, accent, surface, etc.) plus all the parameters
 * needed to render it in any of the five supported styles.
 *
 * The slot carries more than just a base color because the different
 * styles need different supporting colors:
 *
 *  - [base]            the core color used by every style.
 *  - [glow]            the halo color (NEON / COMBINED).
 *  - [metallicStart]   the dark stop of the metallic gradient
 *                      (METALLIC / COMBINED).
 *  - [metallicEnd]     the bright stop of the metallic gradient
 *                      (METALLIC / COMBINED).
 *  - [diffused]        a low-saturation soft color used by the DIFFUSED
 *                      style.
 *  - [style]           which rendering style to use.
 *  - [intensity]       a 0..1 multiplier on the style's visual weight
 *                      (glow radius, gradient stop contrast, etc.).
 *
 * All colors are non-null. The defaults (sensible "titan" neon-cyan
 * fallbacks) make a [ColorSlot] constructable in one line.
 */
data class ColorSlot(
    val base: Color,
    val glow: Color = base,
    val metallicStart: Color = base.copy(alpha = 0.3f),
    val metallicEnd: Color = base,
    val diffused: Color = base.copy(alpha = 0.2f),
    val style: SlotStyle = SlotStyle.NEON,
    val intensity: Float = 1.0f
) {
    init {
        // Defensive: clamp intensity to a sane range. Out-of-range
        // intensities make glow / gradient math produce non-physical
        // results (negative alphas, NaN radii).
        require(intensity in 0f..2f) {
            "ColorSlot.intensity must be in 0..2, was $intensity"
        }
    }

    /**
     * Returns a copy of this slot with a new base color and all derived
     * colors auto-recomputed from the new base. Style + intensity are
     * preserved.
     *
     * The auto-derivation is what makes the color picker feel "alive" —
     * pick a hue, and the slot's glow / metallic stops / diffusion
     * follow without the user having to tune them by hand.
     */
    fun withBase(newBase: Color): ColorSlot {
        return copy(
            base = newBase,
            glow = newBase,
            metallicStart = newBase.copy(alpha = 0.3f),
            metallicEnd = newBase,
            diffused = newBase.copy(alpha = 0.2f)
        )
    }

    companion object {
        /**
         * A neutral default — pure black at zero intensity. Useful as a
         * safe initial value in tests before the real palette is loaded.
         */
        val Default: ColorSlot = ColorSlot(
            base = Color(0xFF000000),
            glow = Color(0xFF000000),
            metallicStart = Color(0xFF000000),
            metallicEnd = Color(0xFF000000),
            diffused = Color(0xFF000000),
            style = SlotStyle.NEON,
            intensity = 0f
        )
    }
}
