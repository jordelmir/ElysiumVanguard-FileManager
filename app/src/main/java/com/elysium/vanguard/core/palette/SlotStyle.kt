package com.elysium.vanguard.core.palette

/**
 * PHASE 10.8 — Visual rendering style for a [ColorSlot].
 *
 * Each style defines how a slot's color is rendered on screen:
 *
 *  - [NEON]           bright core + saturated glow halo. The "default"
 *                     Elysium Vanguard look.
 *  - [PHOSPHORESCENT] green-yellow decay tail — like an afterglow. The
 *                     CRT-terminal feel.
 *  - [METALLIC]       dark → bright gradient. Chrome / gold / brushed
 *                     metal look. Reads as a solid surface with sheen.
 *  - [COMBINED]       neon core + metallic edge. The hybrid style: the
 *                     most "premium" option.
 *  - [DIFFUSED]       low saturation, large-radius low-alpha wash.
 *                     Subdued, faded, ambient.
 *
 * The style is *just* a tag; the actual rendering math lives in
 * [com.elysium.vanguard.ui.theme.SlotRenderers]. The split lets us
 * test the data model without dragging in Compose, and lets us add
 * new styles without touching the data layer.
 */
enum class SlotStyle(val displayName: String, val description: String) {
    NEON(
        displayName = "NEON",
        description = "Saturated core with a glowing halo. The signature Elysium look."
    ),
    PHOSPHORESCENT(
        displayName = "PHOSPHORESCENT",
        description = "Afterglow decay — green-yellow tail like a CRT terminal."
    ),
    METALLIC(
        displayName = "METALLIC",
        description = "Dark to bright gradient. Chrome / gold / brushed metal."
    ),
    COMBINED(
        displayName = "COMBINED",
        description = "Neon core with a metallic edge. The most premium option."
    ),
    DIFFUSED(
        displayName = "DIFFUSED",
        description = "Low-saturation soft wash. Subdued and ambient."
    );

    companion object {
        /** Safe parser — returns NEON for any unknown value. */
        fun fromKey(key: String?): SlotStyle =
            values().firstOrNull { it.name.equals(key, ignoreCase = true) } ?: NEON
    }
}
