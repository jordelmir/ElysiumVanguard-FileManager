package com.elysium.vanguard.core.palette

import androidx.compose.ui.graphics.Color

/**
 * PHASE 10.8 — Built-in palette presets.
 *
 * The user gets these out of the box. Every preset is a real,
 * fully-populated [ColorPalette] — none of the slots are
 * [ColorSlot.Default]. The presets span the five styles:
 *
 *  - **TITAN_DEFAULT**     — the original Elysium Vanguard look
 *                            (cyan / green / red / magenta, all NEON).
 *  - **OLED_BLACK**        — true black background, white-on-black
 *                            text, the four slots dropped to
 *                            monochromatic grey at NEON style 0.6.
 *  - **PHOSPHOR_GREEN**    — full CRT-terminal green-on-black, every
 *                            slot is a green-yellow in PHOSPHORESCENT.
 *  - **CYBER_MAGENTA**     — hot pink / purple / cyan, all NEON.
 *                            The "Cyberpunk 2077" preset.
 *  - **GOLD_METALLIC**     — gold / bronze / silver, all METALLIC. The
 *                            "premium gold" preset.
 *  - **INFRARED**          — deep red / orange / yellow, all COMBINED.
 *                            The "thermal vision" preset.
 *  - **HOLOGRAPHIC**       — cyan / purple / pink, all DIFFUSED. The
 *                            "subtle aurora" preset.
 *  - **MIDNIGHT_NEON**     — deep blue / teal / violet, all NEON at
 *                            intensity 0.7. The "subdued neon" preset.
 *
 * The list is frozen: adding a new preset here is a deliberate
 * product decision and should be reviewed. Renaming an existing one
 * is a breaking change for users who saved a custom palette that
 * referenced the old id.
 */
object PalettePresets {

    /** The original cyan/green/red/magenta look. The Elysium default. */
    val TITAN_DEFAULT: ColorPalette = ColorPalette(
        id = "titan_default",
        name = "TITAN (Default)",
        primary = ColorSlot(
            base = Color(0xFF00FFFF),                 // NeonCyan
            glow = Color(0xFF00FFFF),
            metallicStart = Color(0xFF005C5C),
            metallicEnd = Color(0xFFB0FFFF),
            diffused = Color(0xFF00FFFF).copy(alpha = 0.20f),
            style = SlotStyle.NEON,
            intensity = 1.0f
        ),
        secondary = ColorSlot(
            base = Color(0xFF39FF14),                 // RadioactiveGreen
            glow = Color(0xFF39FF14),
            metallicStart = Color(0xFF0A4D02),
            metallicEnd = Color(0xFFB6FFA0),
            diffused = Color(0xFF39FF14).copy(alpha = 0.20f),
            style = SlotStyle.NEON,
            intensity = 1.0f
        ),
        tertiary = ColorSlot(
            base = Color(0xFFFF073A),                 // NeonRed
            glow = Color(0xFFFF073A),
            metallicStart = Color(0xFF5C000F),
            metallicEnd = Color(0xFFFF99A8),
            diffused = Color(0xFFFF073A).copy(alpha = 0.20f),
            style = SlotStyle.NEON,
            intensity = 1.0f
        ),
        quaternary = ColorSlot(
            base = Color(0xFFB026FF),                 // QuantumPink
            glow = Color(0xFFB026FF),
            metallicStart = Color(0xFF44007A),
            metallicEnd = Color(0xFFE0A0FF),
            diffused = Color(0xFFB026FF).copy(alpha = 0.20f),
            style = SlotStyle.NEON,
            intensity = 1.0f
        ),
        background = Color(0xFF000000),
        surface = Color(0xFF080808),
        isDark = true,
        isBuiltIn = true
    )

    /** True black background, white-on-black, monochrome accents. */
    val OLED_BLACK: ColorPalette = ColorPalette(
        id = "oled_black",
        name = "OLED BLACK",
        primary = ColorSlot(
            base = Color(0xFFFFFFFF),
            glow = Color(0xFFCCCCCC),
            metallicStart = Color(0xFF333333),
            metallicEnd = Color(0xFFFFFFFF),
            diffused = Color(0xFF888888).copy(alpha = 0.10f),
            style = SlotStyle.NEON,
            intensity = 0.6f
        ),
        secondary = ColorSlot(
            base = Color(0xFFB0B0B0),
            glow = Color(0xFF888888),
            metallicStart = Color(0xFF222222),
            metallicEnd = Color(0xFFEEEEEE),
            diffused = Color(0xFF666666).copy(alpha = 0.10f),
            style = SlotStyle.NEON,
            intensity = 0.6f
        ),
        tertiary = ColorSlot(
            base = Color(0xFF808080),
            glow = Color(0xFF606060),
            metallicStart = Color(0xFF1A1A1A),
            metallicEnd = Color(0xFFC0C0C0),
            diffused = Color(0xFF555555).copy(alpha = 0.10f),
            style = SlotStyle.NEON,
            intensity = 0.6f
        ),
        quaternary = ColorSlot(
            base = Color(0xFFD0D0D0),
            glow = Color(0xFFA0A0A0),
            metallicStart = Color(0xFF2A2A2A),
            metallicEnd = Color(0xFFFFFFFF),
            diffused = Color(0xFF999999).copy(alpha = 0.10f),
            style = SlotStyle.NEON,
            intensity = 0.6f
        ),
        background = Color(0xFF000000),
        surface = Color(0xFF000000),
        onBackground = Color(0xFFFFFFFF),
        onSurface = Color(0xFFEEEEEE),
        isDark = true,
        isBuiltIn = true
    )

    /** CRT-terminal green-on-black, PHOSPHORESCENT style on every slot. */
    val PHOSPHOR_GREEN: ColorPalette = ColorPalette(
        id = "phosphor_green",
        name = "PHOSPHOR GREEN",
        primary = ColorSlot(
            base = Color(0xFF00FF66),
            glow = Color(0xFFAAFF00),
            metallicStart = Color(0xFF003311),
            metallicEnd = Color(0xFFAAFFAA),
            diffused = Color(0xFF00FF66).copy(alpha = 0.18f),
            style = SlotStyle.PHOSPHORESCENT,
            intensity = 1.0f
        ),
        secondary = ColorSlot(
            base = Color(0xFFCCFF00),
            glow = Color(0xFFFFFF66),
            metallicStart = Color(0xFF334400),
            metallicEnd = Color(0xFFFFFFAA),
            diffused = Color(0xFFCCFF00).copy(alpha = 0.18f),
            style = SlotStyle.PHOSPHORESCENT,
            intensity = 1.0f
        ),
        tertiary = ColorSlot(
            base = Color(0xFF66FF99),
            glow = Color(0xFFCCFFCC),
            metallicStart = Color(0xFF114422),
            metallicEnd = Color(0xFFCCFFCC),
            diffused = Color(0xFF66FF99).copy(alpha = 0.18f),
            style = SlotStyle.PHOSPHORESCENT,
            intensity = 0.9f
        ),
        quaternary = ColorSlot(
            base = Color(0xFF88FFAA),
            glow = Color(0xFFDDFFDD),
            metallicStart = Color(0xFF225533),
            metallicEnd = Color(0xFFDDFFDD),
            diffused = Color(0xFF88FFAA).copy(alpha = 0.18f),
            style = SlotStyle.PHOSPHORESCENT,
            intensity = 0.8f
        ),
        background = Color(0xFF000000),
        surface = Color(0xFF001A0A),
        onBackground = Color(0xFFCCFFCC),
        onSurface = Color(0xFFAAFFAA),
        isDark = true,
        isBuiltIn = true
    )

    /** Hot pink / purple / cyan, all NEON. The "Cyberpunk" preset. */
    val CYBER_MAGENTA: ColorPalette = ColorPalette(
        id = "cyber_magenta",
        name = "CYBER MAGENTA",
        primary = ColorSlot(
            base = Color(0xFFFF00C8),
            glow = Color(0xFFFF00C8),
            metallicStart = Color(0xFF66004F),
            metallicEnd = Color(0xFFFF99E5),
            diffused = Color(0xFFFF00C8).copy(alpha = 0.20f),
            style = SlotStyle.NEON,
            intensity = 1.0f
        ),
        secondary = ColorSlot(
            base = Color(0xFF00F0FF),
            glow = Color(0xFF00F0FF),
            metallicStart = Color(0xFF004D5C),
            metallicEnd = Color(0xFFA0FFFF),
            diffused = Color(0xFF00F0FF).copy(alpha = 0.20f),
            style = SlotStyle.NEON,
            intensity = 1.0f
        ),
        tertiary = ColorSlot(
            base = Color(0xFF9D00FF),
            glow = Color(0xFF9D00FF),
            metallicStart = Color(0xFF3A0066),
            metallicEnd = Color(0xFFCC99FF),
            diffused = Color(0xFF9D00FF).copy(alpha = 0.20f),
            style = SlotStyle.NEON,
            intensity = 1.0f
        ),
        quaternary = ColorSlot(
            base = Color(0xFFFFEE00),
            glow = Color(0xFFFFFF66),
            metallicStart = Color(0xFF665E00),
            metallicEnd = Color(0xFFFFFFAA),
            diffused = Color(0xFFFFEE00).copy(alpha = 0.20f),
            style = SlotStyle.NEON,
            intensity = 0.9f
        ),
        background = Color(0xFF0A0008),
        surface = Color(0xFF1A0014),
        onBackground = Color(0xFFFFCCE5),
        onSurface = Color(0xFFFFAAE0),
        isDark = true,
        isBuiltIn = true
    )

    /** Gold / bronze / silver, all METALLIC. The "premium gold" preset. */
    val GOLD_METALLIC: ColorPalette = ColorPalette(
        id = "gold_metallic",
        name = "GOLD METALLIC",
        primary = ColorSlot(
            base = Color(0xFFFFD700),
            glow = Color(0xFFFFE657),
            metallicStart = Color(0xFF664400),
            metallicEnd = Color(0xFFFFF6B0),
            diffused = Color(0xFFFFD700).copy(alpha = 0.15f),
            style = SlotStyle.METALLIC,
            intensity = 1.0f
        ),
        secondary = ColorSlot(
            base = Color(0xFFFFAA33),
            glow = Color(0xFFFFCB88),
            metallicStart = Color(0xFF663300),
            metallicEnd = Color(0xFFFFE0BB),
            diffused = Color(0xFFFFAA33).copy(alpha = 0.15f),
            style = SlotStyle.METALLIC,
            intensity = 1.0f
        ),
        tertiary = ColorSlot(
            base = Color(0xFFC0C0C0),
            glow = Color(0xFFE0E0E0),
            metallicStart = Color(0xFF404040),
            metallicEnd = Color(0xFFFFFFFF),
            diffused = Color(0xFFC0C0C0).copy(alpha = 0.12f),
            style = SlotStyle.METALLIC,
            intensity = 0.9f
        ),
        quaternary = ColorSlot(
            base = Color(0xFFCD7F32),
            glow = Color(0xFFE6A876),
            metallicStart = Color(0xFF4D2A0E),
            metallicEnd = Color(0xFFFFD9B8),
            diffused = Color(0xFFCD7F32).copy(alpha = 0.15f),
            style = SlotStyle.METALLIC,
            intensity = 0.95f
        ),
        background = Color(0xFF0A0600),
        surface = Color(0xFF1A1004),
        onBackground = Color(0xFFFFE8B0),
        onSurface = Color(0xFFFFD68A),
        isDark = true,
        isBuiltIn = true
    )

    /** Deep red / orange / yellow, all COMBINED. The "thermal" preset. */
    val INFRARED: ColorPalette = ColorPalette(
        id = "infrared",
        name = "INFRARED",
        primary = ColorSlot(
            base = Color(0xFFFF1A00),
            glow = Color(0xFFFF5C3F),
            metallicStart = Color(0xFF5C0A00),
            metallicEnd = Color(0xFFFFB099),
            diffused = Color(0xFFFF1A00).copy(alpha = 0.20f),
            style = SlotStyle.COMBINED,
            intensity = 1.0f
        ),
        secondary = ColorSlot(
            base = Color(0xFFFF6600),
            glow = Color(0xFFFF993F),
            metallicStart = Color(0xFF5C2400),
            metallicEnd = Color(0xFFFFCC99),
            diffused = Color(0xFFFF6600).copy(alpha = 0.20f),
            style = SlotStyle.COMBINED,
            intensity = 1.0f
        ),
        tertiary = ColorSlot(
            base = Color(0xFFFFAA00),
            glow = Color(0xFFFFCC3F),
            metallicStart = Color(0xFF5C4000),
            metallicEnd = Color(0xFFFFE699),
            diffused = Color(0xFFFFAA00).copy(alpha = 0.18f),
            style = SlotStyle.COMBINED,
            intensity = 0.95f
        ),
        quaternary = ColorSlot(
            base = Color(0xFFFFEE00),
            glow = Color(0xFFFFFF66),
            metallicStart = Color(0xFF5C5700),
            metallicEnd = Color(0xFFFFFFAA),
            diffused = Color(0xFFFFEE00).copy(alpha = 0.18f),
            style = SlotStyle.COMBINED,
            intensity = 0.9f
        ),
        background = Color(0xFF0A0000),
        surface = Color(0xFF1A0606),
        onBackground = Color(0xFFFFCCAA),
        onSurface = Color(0xFFFFAA88),
        isDark = true,
        isBuiltIn = true
    )

    /** Cyan / purple / pink, all DIFFUSED. The "subtle aurora" preset. */
    val HOLOGRAPHIC: ColorPalette = ColorPalette(
        id = "holographic",
        name = "HOLOGRAPHIC",
        primary = ColorSlot(
            base = Color(0xFF00BFFF),
            glow = Color(0xFF66D9FF),
            metallicStart = Color(0xFF003A5C),
            metallicEnd = Color(0xFF99E5FF),
            diffused = Color(0xFF00BFFF).copy(alpha = 0.15f),
            style = SlotStyle.DIFFUSED,
            intensity = 0.8f
        ),
        secondary = ColorSlot(
            base = Color(0xFFAA66FF),
            glow = Color(0xFFCC99FF),
            metallicStart = Color(0xFF331A66),
            metallicEnd = Color(0xFFDDBBFF),
            diffused = Color(0xFFAA66FF).copy(alpha = 0.15f),
            style = SlotStyle.DIFFUSED,
            intensity = 0.8f
        ),
        tertiary = ColorSlot(
            base = Color(0xFFFF66CC),
            glow = Color(0xFFFF99DD),
            metallicStart = Color(0xFF661A4F),
            metallicEnd = Color(0xFFFFBBEE),
            diffused = Color(0xFFFF66CC).copy(alpha = 0.15f),
            style = SlotStyle.DIFFUSED,
            intensity = 0.7f
        ),
        quaternary = ColorSlot(
            base = Color(0xFF66FFCC),
            glow = Color(0xFF99FFDD),
            metallicStart = Color(0xFF1A6650),
            metallicEnd = Color(0xFFBBFFEE),
            diffused = Color(0xFF66FFCC).copy(alpha = 0.15f),
            style = SlotStyle.DIFFUSED,
            intensity = 0.7f
        ),
        background = Color(0xFF000000),
        surface = Color(0xFF050510),
        onBackground = Color(0xFFE0E8FF),
        onSurface = Color(0xFFCCD0FF),
        isDark = true,
        isBuiltIn = true
    )

    /** Deep blue / teal / violet, NEON at 0.7 intensity. Subdued. */
    val MIDNIGHT_NEON: ColorPalette = ColorPalette(
        id = "midnight_neon",
        name = "MIDNIGHT NEON",
        primary = ColorSlot(
            base = Color(0xFF3366FF),
            glow = Color(0xFF6699FF),
            metallicStart = Color(0xFF0A1A5C),
            metallicEnd = Color(0xFF99BBFF),
            diffused = Color(0xFF3366FF).copy(alpha = 0.20f),
            style = SlotStyle.NEON,
            intensity = 0.7f
        ),
        secondary = ColorSlot(
            base = Color(0xFF00CCCC),
            glow = Color(0xFF66E0E0),
            metallicStart = Color(0xFF004D4D),
            metallicEnd = Color(0xFF99EEEE),
            diffused = Color(0xFF00CCCC).copy(alpha = 0.20f),
            style = SlotStyle.NEON,
            intensity = 0.7f
        ),
        tertiary = ColorSlot(
            base = Color(0xFF6633CC),
            glow = Color(0xFF9966FF),
            metallicStart = Color(0xFF1F0A4D),
            metallicEnd = Color(0xFFBB99FF),
            diffused = Color(0xFF6633CC).copy(alpha = 0.20f),
            style = SlotStyle.NEON,
            intensity = 0.7f
        ),
        quaternary = ColorSlot(
            base = Color(0xFF0099FF),
            glow = Color(0xFF66BBFF),
            metallicStart = Color(0xFF003A5C),
            metallicEnd = Color(0xFF99DDFF),
            diffused = Color(0xFF0099FF).copy(alpha = 0.20f),
            style = SlotStyle.NEON,
            intensity = 0.7f
        ),
        background = Color(0xFF000510),
        surface = Color(0xFF050A1A),
        onBackground = Color(0xFFCCD0FF),
        onSurface = Color(0xFFB0BBEE),
        isDark = true,
        isBuiltIn = true
    )

    /**
     * The complete list of built-in presets in display order. The
     * customization screen renders this list and lets the user tap
     * any preset to apply it.
     */
    val ALL: List<ColorPalette> = listOf(
        TITAN_DEFAULT,
        OLED_BLACK,
        PHOSPHOR_GREEN,
        CYBER_MAGENTA,
        GOLD_METALLIC,
        INFRARED,
        HOLOGRAPHIC,
        MIDNIGHT_NEON
    )

    /**
     * Lookup by stable id. Returns [TITAN_DEFAULT] if the id is
     * unknown — that's a safe fallback for the first launch case
     * where the persisted id might be from an older schema.
     */
    fun byId(id: String?): ColorPalette =
        ALL.firstOrNull { it.id == id } ?: TITAN_DEFAULT

    /**
     * The default palette the app shows on a fresh install.
     */
    val Default: ColorPalette = TITAN_DEFAULT
}
