package com.elysium.vanguard.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
 *
 * PHASE 127 — added [themeMode] parameter. The user can pick
 * Dark, Light, or System from the Settings body of the
 * proprietary Windows desktop. Light uses M3's
 * [lightColorScheme] (with a slightly desaturated palette);
 * System reads the OS preference via [isSystemInDarkTheme].
 */
enum class ThemeMode { Dark, Light, System }

@Composable
fun ElysiumTheme(
    palette: ColorPalette = PalettePresets.Default,
    themeMode: ThemeMode = ThemeMode.Dark,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val colorScheme = remember(palette, useDark) {
        if (useDark) {
            darkColorScheme(
                primary = palette.primary.base,
                onPrimary = Color.Black,
                secondary = palette.secondary.base,
                onSecondary = Color.Black,
                tertiary = palette.tertiary.base,
                onTertiary = Color.Black,
                background = palette.background,
                onBackground = palette.onBackground,
                surface = palette.surface,
                onSurface = palette.onSurface
            )
        } else {
            // Light mode: invert the surface / background so
            // text-on-surface remains readable. The four
            // accent slots stay the same; they're already
            // saturation-tuned.
            lightColorScheme(
                primary = palette.primary.base,
                onPrimary = Color.White,
                secondary = palette.secondary.base,
                onSecondary = Color.White,
                tertiary = palette.tertiary.base,
                onTertiary = Color.White,
                background = Color(0xFFF7F7F8),
                onBackground = Color(0xFF1A1A1F),
                surface = Color.White,
                onSurface = Color(0xFF1A1A1F),
            )
        }
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
