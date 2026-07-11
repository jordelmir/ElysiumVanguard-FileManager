package com.elysium.vanguard.features.customization

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elysium.vanguard.core.palette.ColorSlot
import com.elysium.vanguard.core.palette.SlotStyle
import com.elysium.vanguard.ui.theme.SlotRenderers
import com.elysium.vanguard.ui.theme.TitanColors

/**
 * PHASE 10.8 — Editor card for one [ColorSlot].
 *
 * The card has four regions, top to bottom:
 *
 *  1. Header — slot name, color swatch (taps open the picker),
 *     hex code.
 *  2. Style selector — five pill buttons for the five
 *     [SlotStyle]s. The selected one is filled; the others are
 *     outlined.
 *  3. Intensity slider — 0..2 with 1.0 as the default. The
 *     preview updates live as the user drags.
 *  4. Live preview — a `slotGlow`-rendered tile showing the
 *     current base + style + intensity.
 */
@Composable
fun SlotEditorCard(
    slotName: String,
    slot: ColorSlot,
    onBaseChange: (Color) -> Unit,
    onStyleChange: (SlotStyle) -> Unit,
    onIntensityChange: (Float) -> Unit
) {
    var pickerOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(TitanColors.CarbonGray.copy(alpha = 0.6f))
            .border(1.dp, TitanColors.AbsoluteWhite.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        // ── Header ─────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                slotName,
                color = TitanColors.AbsoluteWhite,
                fontWeight = FontWeight.Black,
                fontSize = 14.sp,
                letterSpacing = 2.sp,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(slot.base)
                    .border(2.dp, TitanColors.AbsoluteWhite.copy(alpha = 0.4f), CircleShape)
                    .clickable { pickerOpen = true }
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "#%06X".format(((slot.base.value and 0xFFFFFFuL).toInt())),
                color = TitanColors.AbsoluteWhite.copy(alpha = 0.5f),
                fontSize = 10.sp
            )
        }

        Spacer(Modifier.height(10.dp))

        // ── Style selector ─────────────────────────────────────
        Text(
            "STYLE",
            color = TitanColors.AbsoluteWhite.copy(alpha = 0.5f),
            fontSize = 10.sp,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(SlotStyle.values().toList()) { style ->
                StylePill(
                    style = style,
                    selected = style == slot.style,
                    onClick = { onStyleChange(style) }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // ── Intensity slider ───────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "INTENSITY",
                color = TitanColors.AbsoluteWhite.copy(alpha = 0.5f),
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                modifier = Modifier.width(80.dp)
            )
            Slider(
                value = slot.intensity,
                onValueChange = onIntensityChange,
                valueRange = 0f..2f,
                colors = SliderDefaults.colors(
                    thumbColor = slot.base,
                    activeTrackColor = slot.base.copy(alpha = 0.7f),
                    inactiveTrackColor = TitanColors.AbsoluteWhite.copy(alpha = 0.1f)
                ),
                modifier = Modifier.weight(1f)
            )
            Text(
                "%.1f".format(slot.intensity),
                color = TitanColors.AbsoluteWhite.copy(alpha = 0.6f),
                fontSize = 10.sp,
                modifier = Modifier.width(28.dp)
            )
        }

        Spacer(Modifier.height(10.dp))

        // ── Live preview ───────────────────────────────────────
        Text(
            "PREVIEW",
            color = TitanColors.AbsoluteWhite.copy(alpha = 0.5f),
            fontSize = 10.sp,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .then(SlotRenderers.run { Modifier.slotGlow(slot, cornerRadius = 12.dp, glowRadius = 14.dp) })
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    slotName,
                    color = TitanColors.AbsoluteWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    letterSpacing = 2.sp
                )
            }
        }
    }

    if (pickerOpen) {
        ColorPickerDialog(
            initialColor = slot.base,
            onColorSelected = onBaseChange,
            onDismiss = { pickerOpen = false },
            title = "$slotName COLOR"
        )
    }
}

/**
 * A small pill button for one [SlotStyle]. Selected = filled
 * with the slot's base color + a thin border. Unselected =
 * outlined only.
 */
@Composable
private fun StylePill(
    style: SlotStyle,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (selected) TitanColors.NeonCyan.copy(alpha = 0.25f) else Color.Transparent
    val border = if (selected) TitanColors.NeonCyan else TitanColors.AbsoluteWhite.copy(alpha = 0.2f)
    val textColor = if (selected) TitanColors.NeonCyan else TitanColors.AbsoluteWhite.copy(alpha = 0.7f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            style.displayName,
            color = textColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
}
