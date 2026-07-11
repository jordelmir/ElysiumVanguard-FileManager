package com.elysium.vanguard.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.elysium.vanguard.core.palette.ColorPalette
import com.elysium.vanguard.core.palette.PalettePresets

/**
 * PHASE 10.8 — CompositionLocal for the active [ColorPalette].
 *
 * Use this in any composable that needs the current palette:
 *
 *     val palette = LocalPalette.current
 *     Box(modifier = Modifier.slotGlow(palette.primary))
 *
 * The default value is the TITAN preset so a composable that runs
 * outside an [ElysiumTheme] still has a sensible palette to read
 * from (avoids NPEs in previews and isolated tests).
 *
 * We use [staticCompositionLocalOf] instead of [compositionLocalOf]
 * because the palette is read at the top of the tree (theme) and
 * not tracked at the call site. staticCompositionLocalOf skips
 * invalidation tracking for non-React-like flows and gives a small
 * perf win.
 */
val LocalPalette = staticCompositionLocalOf<ColorPalette> {
    PalettePresets.Default
}
