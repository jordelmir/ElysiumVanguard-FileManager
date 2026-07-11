package com.elysium.vanguard.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elysium.vanguard.core.palette.ColorSlot
import com.elysium.vanguard.core.palette.SlotStyle

/**
 * PHASE 10.8 — Renderers that turn a [ColorSlot] into a visual
 * border / background / glow, picking the right math for the
 * slot's [SlotStyle].
 *
 * Five styles, five renderings. The renderers are stateless: they
 * look at the slot and produce a Modifier chain. That makes them
 * trivial to use inside any composable.
 *
 * The renderers are deliberately conservative — they don't animate
 * the slot's intensity, so a style change is an instant flip. The
 * [pulsingNeonBorder] modifier (which predates this phase) is
 * unchanged: that one's the only thing in the codebase that
 * animates a color, and it's still useful for "alive" surfaces.
 */
object SlotRenderers {

    // ── glow / border for a slot ────────────────────────────────

    /**
     * Apply a border whose color and stroke depend on the slot's
     * style. The corner radius and stroke width are tweakable.
     */
    fun Modifier.slotBorder(
        slot: ColorSlot,
        cornerRadius: Dp = 16.dp,
        strokeWidth: Dp = 1.5.dp
    ): Modifier = composed {
        val (brush, _) = borderBrushAndSpotFor(slot)
        this
            .clip(RoundedCornerShape(cornerRadius))
            .border(
                width = strokeWidth,
                brush = brush,
                shape = RoundedCornerShape(cornerRadius)
            )
    }

    /**
     * Apply a background fill whose alpha + gradient depend on the
     * slot's style. The "background" version uses the slot's base
     * color with style-specific alpha tweaks.
     */
    fun Modifier.slotBackground(
        slot: ColorSlot,
        cornerRadius: Dp = 16.dp,
        alpha: Float? = null
    ): Modifier = composed {
        val effectiveAlpha = alpha ?: defaultAlphaFor(slot.style) * slot.intensity.coerceIn(0f, 1f)
        val fillColor = slot.base.copy(alpha = effectiveAlpha.coerceIn(0f, 1f))
        this
            .clip(RoundedCornerShape(cornerRadius))
            .background(fillColor)
    }

    /**
     * Apply both a background fill AND a glowing shadow whose
     * radius + spot color depend on the slot's style. The "premium"
     * version of [slotBackground].
     */
    fun Modifier.slotGlow(
        slot: ColorSlot,
        cornerRadius: Dp = 16.dp,
        glowRadius: Dp = 12.dp
    ): Modifier = composed {
        val (brush, spot) = borderBrushAndSpotFor(slot)
        val effectiveGlow = (glowRadius.value * slot.intensity.coerceIn(0f, 2f)).dp
        val shape = RoundedCornerShape(cornerRadius)
        this
            .shadow(
                elevation = effectiveGlow,
                shape = shape,
                spotColor = spot,
                ambientColor = spot.copy(alpha = spot.alpha * 0.5f),
                clip = false
            )
            .clip(shape)
            .background(slot.base.copy(alpha = defaultAlphaFor(slot.style) * slot.intensity.coerceIn(0f, 1f)))
            .border(width = 1.5.dp, brush = brush, shape = shape)
    }

    // ── brush + spot color per style ────────────────────────────

    /**
     * Returns the border brush + the spot color for a slot's
     * shadow, in the slot's style. Pulled out so all three
     * modifiers agree on the colors.
     */
    private fun borderBrushAndSpotFor(slot: ColorSlot): Pair<Brush, Color> {
        val i = slot.intensity.coerceIn(0f, 2f)
        return when (slot.style) {
            SlotStyle.NEON -> {
                // Saturated vertical gradient from a bright top to a
                // dim bottom, with the slot's own color as the spot.
                Pair(
                    Brush.verticalGradient(
                        listOf(
                            slot.glow.copy(alpha = 0.9f * i),
                            slot.glow.copy(alpha = 0.2f * i),
                            slot.glow.copy(alpha = 0.7f * i)
                        )
                    ),
                    slot.glow
                )
            }
            SlotStyle.PHOSPHORESCENT -> {
                // Green-yellow decay tail: a green core at top
                // fading to the slot's diffused color at the
                // bottom. The "afterglow" feel.
                Pair(
                    Brush.verticalGradient(
                        listOf(
                            slot.base,
                            slot.diffused,
                            Color.Transparent
                        )
                    ),
                    slot.glow
                )
            }
            SlotStyle.METALLIC -> {
                // Dark→bright gradient. The metallic feel.
                Pair(
                    Brush.linearGradient(
                        listOf(
                            slot.metallicStart,
                            slot.base,
                            slot.metallicEnd
                        )
                    ),
                    slot.metallicEnd
                )
            }
            SlotStyle.COMBINED -> {
                // Neon core with a metallic edge: the inner brush
                // is a vertical neon fade, the spot is the metallic
                // end (chrome edge).
                Pair(
                    Brush.verticalGradient(
                        listOf(
                            slot.glow.copy(alpha = 0.9f * i),
                            slot.base,
                            slot.metallicEnd.copy(alpha = 0.4f * i)
                        )
                    ),
                    slot.metallicEnd
                )
            }
            SlotStyle.DIFFUSED -> {
                // Soft, low-saturation wash. Big gradient, low
                // alpha, no spot glow.
                Pair(
                    Brush.radialGradient(
                        listOf(
                            slot.base.copy(alpha = 0.25f * i),
                            slot.diffused,
                            Color.Transparent
                        )
                    ),
                    slot.diffused
                )
            }
        }
    }

    /**
     * The default background alpha for a style. NEON and COMBINED
     * are vivid; PHOSPHORESCENT and METALLIC are surface-like;
     * DIFFUSED is whisper-quiet.
     */
    private fun defaultAlphaFor(style: SlotStyle): Float = when (style) {
        SlotStyle.NEON -> 0.10f
        SlotStyle.PHOSPHORESCENT -> 0.08f
        SlotStyle.METALLIC -> 0.18f
        SlotStyle.COMBINED -> 0.12f
        SlotStyle.DIFFUSED -> 0.04f
    }
}
