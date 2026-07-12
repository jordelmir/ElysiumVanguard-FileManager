package com.elysium.vanguard.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.elysium.vanguard.core.palette.ColorPalette
import com.elysium.vanguard.core.palette.PalettePresets

/**
 * PHASE 10.8/10.9 — Theme that wires a [ColorPalette] into the
 * Compose tree.
 *
 * The theme does three things:
 *
 *  1. Sets the legacy [MaterialTheme] color scheme so M3 widgets
 *     (Button, TextField, etc.) pick up the primary/secondary
 *     colors from the palette. We translate the palette's
 *     four accent slots into the M3 primary/secondary/tertiary
 *     slots.
 *  2. Provides the palette itself via [LocalPalette] so custom
 *     widgets can read it directly via
 *     `val palette = LocalPalette.current`.
 *  3. (PHASE 10.9) Publishes a [GlobalThemeColors] view of the
 *     palette via [LocalGlobalTheme] so any composable can read
 *     `GlobalColors.primary` (etc.) without going through the
 *     full [ColorPalette]. This is the channel the rest of the
 *     app uses to theme its cards, tiles, and glass surfaces.
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

    val globalColors = remember(palette) {
        GlobalThemeColors(
            primary = palette.primary.base,
            secondary = palette.secondary.base,
            tertiary = palette.tertiary.base,
            quaternary = palette.quaternary.base
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val adaptiveMetrics = remember(maxWidth, maxHeight) {
            adaptiveMetricsFor(maxWidth, maxHeight)
        }

        CompositionLocalProvider(
            LocalPalette provides palette,
            LocalGlobalTheme provides globalColors,
            LocalAdaptiveMetrics provides adaptiveMetrics
        ) {
            MaterialTheme(
                colorScheme = colorScheme,
                typography = TitanTypography,
                content = content
            )
        }
    }
}
