package com.elysium.vanguard.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.elysium.vanguard.core.palette.ColorPalette
import com.elysium.vanguard.core.palette.PalettePresets

/**
 * PHASE 10.9 — Global theme container.
 *
 * The whole app reads from this theme instead of hardcoded
 * [TitanColors]. Four colors:
 *
 *  - [primary]    — main accent (buttons, primary CTAs, main glow)
 *  - [secondary]  — secondary accent (alternate actions, paired highlights)
 *  - [tertiary]   — tertiary accent (alternative surfaces, contrast)
 *  - [quaternary] — muted accent (low-priority surfaces, ambient)
 *
 * Sourcing: each slot reads from [LocalPalette.current]'s matching
 * [ColorSlot] (`.primary.base`, `.secondary.base`, etc.). When the
 * user picks a new palette on the COLORS screen, the entire app
 * re-renders in the new colors — no rebuild, no per-screen work.
 *
 * Usage:
 *
 *     Box(
 *         modifier = Modifier
 *             .pulsingNeonBorder(glowColor = GlobalColors.primary)
 *     ) { ... }
 *
 * Or read directly:
 *
 *     val primary = GlobalColors.primary
 *
 * The default value (TITAN preset) ensures previews and isolated
 * tests have a sensible theme without an [GlobalTheme] wrapper.
 */
data class GlobalThemeColors(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val quaternary: Color
) {
    /** All four in declaration order, for the [LocalGlobalTheme] default. */
    val all: List<Color> get() = listOf(primary, secondary, tertiary, quaternary)
}

/**
 * CompositionLocal for the global theme. Read via
 * `LocalGlobalTheme.current` (rare — most code uses
 * [GlobalColors] instead, which is the same thing but with a
 * shorter call site).
 */
val LocalGlobalTheme = staticCompositionLocalOf<GlobalThemeColors> {
    val default = PalettePresets.Default
    GlobalThemeColors(
        primary = default.primary.base,
        secondary = default.secondary.base,
        tertiary = default.tertiary.base,
        quaternary = default.quaternary.base
    )
}

/**
 * Composable that publishes a [GlobalThemeColors] derived from
 * the current [LocalPalette]. Wrap your content with this at
 * the root of the tree (or under a [ElysiumTheme]) to make
 * [GlobalColors] return the live palette values.
 *
 * If no [palette] is provided, the global theme defaults to
 * TITAN preset colors. This matches [ElysiumTheme]'s default
 * and keeps previews / tests working.
 */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
fun rememberGlobalThemeColors(palette: ColorPalette? = null): GlobalThemeColors {
    val source = palette
    val primary = source?.primary?.base
        ?: LocalPalette.current.primary.base
    val secondary = source?.secondary?.base
        ?: LocalPalette.current.secondary.base
    val tertiary = source?.tertiary?.base
        ?: LocalPalette.current.tertiary.base
    val quaternary = source?.quaternary?.base
        ?: LocalPalette.current.quaternary.base
    return GlobalThemeColors(primary, secondary, tertiary, quaternary)
}

/**
 * MaterialTheme-style accessors for the global theme. Read
 * inside any composable that's wrapped in a [GlobalTheme] (or
 * [ElysiumTheme] — they both wire the same CompositionLocal).
 *
 *     val c = GlobalColors.primary
 *     PulsingNeonBorder(color = c)
 *
 * Defaults to the TITAN preset colors if no theme is provided.
 */
object GlobalColors {
    val primary: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalGlobalTheme.current.primary

    val secondary: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalGlobalTheme.current.secondary

    val tertiary: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalGlobalTheme.current.tertiary

    val quaternary: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalGlobalTheme.current.quaternary

    /**
     * All four colors in order. Useful for things like the
     * color-wheel icon that sweeps through the whole palette.
     */
    val all: List<Color>
        @Composable
        @ReadOnlyComposable
        get() = LocalGlobalTheme.current.all
}
