package com.elysium.vanguard.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.elysium.vanguard.core.palette.ColorPalette
import com.elysium.vanguard.core.palette.PalettePresets

/**
 * PHASE 10.8 — Theme that wires a [ColorPalette] into the
 * Compose tree.
 *
 * The theme does two things:
 *
 *  1. Sets the legacy [MaterialTheme] color scheme so M3 widgets
 *     (Button, TextField, etc.) pick up the primary/secondary
 *     colors from the palette. We translate the palette's
 *     four accent slots into the M3 primary/secondary/tertiary
 *     slots.
 *  2. Provides the palette itself via [LocalPalette] so custom
 *     widgets can read it directly via
 *     `val palette = LocalPalette.current`.
 *
 * The theme takes the palette as a parameter — the caller is
 * expected to read it from a ViewModel (or any other source).
 * This keeps the theme itself free of Hilt / ViewModel lookups
 * so it works in previews, in tests, and at the root of
 * setContent.
 */
@Composable
fun ElysiumTheme(
    palette: ColorPalette = PalettePresets.Default,
    content: @Composable () -> Unit
) {
    val colorScheme = remember(palette) {
        darkColorScheme(
            primary = palette.primary.base,
            onPrimary = if (palette.isDark) Color.Black else Color.White,
            secondary = palette.secondary.base,
            onSecondary = if (palette.isDark) Color.Black else Color.White,
            tertiary = palette.tertiary.base,
            onTertiary = if (palette.isDark) Color.Black else Color.White,
            background = palette.background,
            onBackground = palette.onBackground,
            surface = palette.surface,
            onSurface = palette.onSurface
        )
    }

    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = TitanTypography,
            content = content
        )
    }
}
