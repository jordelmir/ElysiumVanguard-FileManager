package com.elysium.vanguard.ui.theme

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * ELYSIUM GLASS EFFECTS SUITE
 * Premium glass, reactor glass, neon glass, holographic glass, and pulsing neon borders.
 */

// ── PREMIUM GLASS ──
fun Modifier.premiumGlass(
    cornerRadius: Dp = 16.dp,
    borderColor: Color = Color.White.copy(alpha = 0.2f),
    glassAlpha: Float = 0.3f,
    glowRadius: Dp = 14.dp
): Modifier = composed {
    val shape = RoundedCornerShape(cornerRadius)
    this
        .then(
            if (glowRadius > 0.dp) {
                Modifier.shadow(
                    elevation = glowRadius,
                    shape = shape,
                    spotColor = borderColor.copy(alpha = 0.85f),
                    ambientColor = borderColor.copy(alpha = 0.65f),
                    clip = false
                )
            } else Modifier
        )
        .clip(shape)
        .background(neonSurfaceBrush(borderColor, glassAlpha))
        .border(
            width = 1.2.dp,
            brush = Brush.verticalGradient(
                colors = listOf(
                    borderColor.copy(alpha = 0.7f),
                    Color.Transparent,
                    borderColor.copy(alpha = 0.35f)
                )
            ),
            shape = shape
        )
}

// ── REACTOR GLASS ──
fun Modifier.reactorGlass(
    cornerRadius: Dp = 20.dp,
    glowColor: Color = TitanColors.NeonCyan,
    alpha: Float = 0.45f,
    glowRadius: Dp = 18.dp
): Modifier = composed {
    val shape = RoundedCornerShape(cornerRadius)
    this
        .shadow(
            elevation = glowRadius,
            shape = shape,
            spotColor = glowColor.copy(alpha = 0.95f),
            ambientColor = glowColor.copy(alpha = 0.75f),
            clip = false
        )
        .clip(shape)
        .background(neonSurfaceBrush(glowColor, alpha))
        .border(
            width = 1.5.dp,
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.35f),
                    Color.Transparent,
                    Color.White.copy(alpha = 0.15f)
                )
            ),
            shape = shape
        )
}

// ── NEON GLASS ──
fun Modifier.neonGlass(
    cornerRadius: Dp = 16.dp,
    glowColor: Color = TitanColors.NeonCyan,
    strokeWidth: Dp = 1.2.dp
): Modifier = composed {
    val shape = RoundedCornerShape(cornerRadius)
    this
        .shadow(
            elevation = 22.dp,
            shape = shape,
            spotColor = glowColor.copy(alpha = 0.95f),
            ambientColor = glowColor.copy(alpha = 0.7f),
            clip = false
        )
        .clip(shape)
        .background(neonSurfaceBrush(glowColor, 0.22f))
        .border(
            width = strokeWidth,
            brush = Brush.verticalGradient(
                colors = listOf(
                    glowColor.copy(alpha = 0.95f),
                    Color.Transparent,
                    glowColor.copy(alpha = 0.5f)
                )
            ),
            shape = shape
        )
}

// ── HOLOGRAPHIC GLASS ── (Multi-color shifting border)
fun Modifier.holographicGlass(
    cornerRadius: Dp = 20.dp,
    glassAlpha: Float = 0.25f,
    glowRadius: Dp = 22.dp
): Modifier = composed {
    val shape = RoundedCornerShape(cornerRadius)
    val infiniteTransition = rememberInfiniteTransition(label = "holo")
    
    val hueShift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "hue"
    )
    
    val borderColors = listOf(
        TitanColors.NeonCyan,
        TitanColors.PlasmaPurple,
        TitanColors.QuantumPink,
        TitanColors.NeonOrange,
        TitanColors.NeonYellow,
        TitanColors.RadioactiveGreen,
        TitanColors.NeonCyan
    )
    
    val startIndex = (hueShift * (borderColors.size - 1)).toInt().coerceIn(0, borderColors.size - 2)
    val fraction = (hueShift * (borderColors.size - 1)) - startIndex
    val currentColor = lerp(borderColors[startIndex], borderColors[startIndex + 1], fraction)

    this
        .shadow(
            elevation = glowRadius,
            shape = shape,
            spotColor = currentColor.copy(alpha = 0.85f),
            ambientColor = currentColor.copy(alpha = 0.55f),
            clip = false
        )
        .clip(shape)
        .background(neonSurfaceBrush(currentColor, glassAlpha))
        .border(
            width = 1.8.dp,
            brush = Brush.linearGradient(
                colors = listOf(
                    currentColor.copy(alpha = 0.95f),
                    currentColor.copy(alpha = 0.3f),
                    currentColor.copy(alpha = 0.8f)
                )
            ),
            shape = shape
        )
}

// ── PULSING NEON BORDER ── (Alive breathing border)
fun Modifier.pulsingNeonBorder(
    cornerRadius: Dp = 20.dp,
    glowColor: Color = TitanColors.NeonCyan,
    glassAlpha: Float = 0.2f,
    glowRadius: Dp = 14.dp
): Modifier = composed {
    val shape = RoundedCornerShape(cornerRadius)
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    
    val borderAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "border_alpha"
    )
    
    val glowElevation by infiniteTransition.animateFloat(
        initialValue = glowRadius.value * 0.5f,
        targetValue = glowRadius.value * 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_elevation"
    )

    this
        .shadow(
            elevation = glowElevation.dp,
            shape = shape,
            spotColor = glowColor.copy(alpha = borderAlpha * 0.6f),
            ambientColor = glowColor.copy(alpha = borderAlpha * 0.4f),
            clip = false
        )
        .clip(shape)
        .background(neonSurfaceBrush(glowColor, glassAlpha))
        .border(
            width = 1.5.dp,
            brush = Brush.verticalGradient(
                colors = listOf(
                    glowColor.copy(alpha = borderAlpha),
                    glowColor.copy(alpha = borderAlpha * 0.3f),
                    glowColor.copy(alpha = borderAlpha * 0.8f)
                )
            ),
            shape = shape
        )
}

// ── COLOR LERP HELPER ──
private fun lerp(start: Color, stop: Color, fraction: Float): Color {
    return Color(
        red = start.red + (stop.red - start.red) * fraction,
        green = start.green + (stop.green - start.green) * fraction,
        blue = start.blue + (stop.blue - start.blue) * fraction,
        alpha = start.alpha + (stop.alpha - start.alpha) * fraction
    )
}

/**
 * Shared translucent fill for every glass/neon surface.
 *
 * The old suite used black or carbon-gray fills, which made repeated
 * rectangular "holes" show up inside cards across the APK. This keeps the
 * cyber-dark mood but derives the interior from the active glow color, so
 * every surface reads as a lit pane instead of a black patch.
 */
internal fun uniformNeonSurfaceColor(color: Color, intensity: Float): Color {
    val strength = intensity.coerceIn(0f, 1f)
    if (strength == 0f) return Color.Transparent
    return Color(
        red = color.red * strength,
        green = color.green * strength,
        blue = color.blue * strength,
        alpha = 1f
    )
}

internal fun uniformNeonSurfaceBrush(color: Color, intensity: Float): Brush {
    val solid = uniformNeonSurfaceColor(color, intensity)
    return Brush.linearGradient(colors = listOf(solid, solid))
}

private fun neonSurfaceBrush(color: Color, alpha: Float): Brush {
    val a = alpha.coerceIn(0f, 1f)
    val surfaceIntensity = (a * 0.70f).coerceAtMost(0.22f)
    return uniformNeonSurfaceBrush(color, surfaceIntensity)
}
