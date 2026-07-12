package com.elysium.vanguard.ui.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * PHASE 10.9 — Luminous frame.
 *
 * The opposite of the dark-glass look. No background fill. Just
 * a colored border on the edge of the shape and a soft outer
 * shadow that radiates OUTSIDE the shape (via `clip = false`).
 *
 * The intent: when the user says "el color y brillo debe ir por
 * fuera" / "el borde luminoso por fuera del contenedor", this is
 * the modifier they want. The container becomes a transparent
 * window framed by a glowing edge, so the page background shows
 * through the interior and the icon / content floats inside
 * the luminous border.
 *
 * The color defaults to [GlobalColors.primary] so calling
 * `Modifier.luminousFrame()` inside a composable under
 * [ElysiumTheme] automatically picks up the active palette.
 * Pass an explicit `color` to override (per-section accents,
 * status semantics, etc.).
 *
 * Usage:
 *
 *     Box(
 *         modifier = Modifier
 *             .size(48.dp)
 *             .luminousFrame(color = neonColor)   // no background, just a glowing border
 *     ) {
 *         Icon(...)
 *     }
 *
 * The outer glow is `clip = false` so it extends past the shape
 * — that's the "border on the outside". The border is drawn on
 * the edge (no inner fill) so the container's interior is the
 * page's surface, not a separate dark layer.
 */
fun Modifier.luminousFrame(
    cornerRadius: Dp = 16.dp,
    color: Color? = null,
    glowRadius: Dp = 18.dp,
    borderWidth: Dp = 1.5.dp
): Modifier = composed {
    val effectiveColor = color ?: currentLuminousFrameColor()
    val shape = RoundedCornerShape(cornerRadius)
    this
        .shadow(
            elevation = glowRadius,
            shape = shape,
            spotColor = effectiveColor.copy(alpha = 0.95f),
            ambientColor = effectiveColor.copy(alpha = 0.7f),
            clip = false
        )
        .border(
            width = borderWidth,
            brush = Brush.verticalGradient(
                colors = listOf(
                    effectiveColor.copy(alpha = 0.9f),
                    effectiveColor.copy(alpha = 0.4f),
                    effectiveColor.copy(alpha = 0.75f)
                )
            ),
            shape = shape
        )
}

/**
 * Default color for [luminousFrame] when none is supplied. Reads
 * the primary from the live [GlobalColors] theme so the frame
 * matches the palette the user picked on the COLORS screen.
 */
@Composable
@ReadOnlyComposable
private fun currentLuminousFrameColor(): Color = GlobalColors.primary
