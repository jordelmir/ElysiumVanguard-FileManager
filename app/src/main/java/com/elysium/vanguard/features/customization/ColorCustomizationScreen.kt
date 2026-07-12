package com.elysium.vanguard.features.customization

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.elysium.vanguard.core.palette.ColorPalette
import com.elysium.vanguard.core.palette.SlotStyle
import com.elysium.vanguard.ui.theme.GlobalColors
import com.elysium.vanguard.ui.theme.LocalAdaptiveMetrics
import com.elysium.vanguard.ui.theme.SlotRenderers
import com.elysium.vanguard.ui.theme.TitanColors

/**
 * PHASE 10.8 — Top-level color customization screen.
 *
 * Three sections, top to bottom:
 *
 *  1. **Presets** — 8 built-in palettes (TITAN / OLED BLACK /
 *     PHOSPHOR GREEN / CYBER MAGENTA / GOLD METALLIC / INFRARED /
 *     HOLOGRAPHIC / MIDNIGHT NEON) as cards. Tap to apply.
 *  2. **Slot editors** — one [SlotEditorCard] per slot
 *     (PRIMARY, SECONDARY, TERTIARY, QUATERNARY, ACCENT). Live
 *     preview, color picker, style selector, intensity slider.
 *  3. **Saved palettes** — user-saved palettes with a delete
 *     button. Plus a "Save current as..." text field + SAVE
 *     button.
 *
 * The whole screen is wrapped in a vertical scroll. The header
 * has a back button, a "COLORS" title, and a reset button.
 */
@Composable
fun ColorCustomizationScreen(
    onBack: () -> Unit,
    viewModel: ColorCustomizationViewModel = hiltViewModel()
) {
    val palette by viewModel.palette.collectAsState()
    val presets by viewModel.presets.collectAsState()
    val saved by viewModel.saved.collectAsState()
    val draftName by viewModel.draftName.collectAsState()
    val adaptive = LocalAdaptiveMetrics.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = adaptive.screenPadding, vertical = adaptive.screenPadding / 2)
        ) {
            Header(
                paletteName = palette.name,
                onBack = onBack,
                onReset = { viewModel.resetToDefault() }
            )

            Spacer(Modifier.height(12.dp))

            // ── Presets ─────────────────────────────────────────
            SectionLabel("PRESETS")
            Spacer(Modifier.height(6.dp))
            PresetRow(
                presets = presets,
                activeId = palette.id,
                onApply = { viewModel.applyPreset(it) }
            )

            Spacer(Modifier.height(16.dp))

            // ── Slot editors ───────────────────────────────────
            SectionLabel("SLOTS")
            Spacer(Modifier.height(6.dp))
            ColorPalette.SLOT_NAMES.forEach { slotName ->
                val slot = slotByName(palette, slotName)
                SlotEditorCard(
                    slotName = slotName,
                    slot = slot,
                    onBaseChange = { viewModel.updateSlotBase(slotName, it) },
                    onStyleChange = { viewModel.updateSlotStyle(slotName, it) },
                    onIntensityChange = { viewModel.updateSlotIntensity(slotName, it) }
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── Save current ───────────────────────────────────
            SectionLabel("SAVE CURRENT PALETTE")
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { viewModel.setDraftName(it) },
                    label = { Text("Name", color = TitanColors.AbsoluteWhite.copy(alpha = 0.5f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TitanColors.NeonCyan,
                        unfocusedBorderColor = TitanColors.AbsoluteWhite.copy(alpha = 0.2f),
                        focusedTextColor = TitanColors.AbsoluteWhite,
                        unfocusedTextColor = TitanColors.AbsoluteWhite,
                        cursorColor = TitanColors.NeonCyan
                    ),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                SaveButton(onClick = { viewModel.saveCurrent() })
            }

            Spacer(Modifier.height(16.dp))

            // ── Saved palettes ─────────────────────────────────
            if (saved.isNotEmpty()) {
                SectionLabel("YOUR PALETTES")
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    saved.forEach { p ->
                        SavedPaletteRow(
                            palette = p,
                            isActive = p.id == palette.id,
                            onApply = { viewModel.applySaved(p.id) },
                            onDelete = { viewModel.deleteSaved(p.id) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun Header(paletteName: String, onBack: () -> Unit, onReset: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = TitanColors.NeonCyan
            )
        }
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "COLORS",
                color = TitanColors.AbsoluteWhite,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.sp
            )
            Text(
                "Currently: $paletteName",
                color = TitanColors.AbsoluteWhite.copy(alpha = 0.5f),
                fontSize = 12.sp
            )
        }
        IconButton(onClick = onReset) {
            Icon(
                Icons.Default.Restore,
                contentDescription = "Reset to default",
                tint = TitanColors.AbsoluteWhite.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = GlobalColors.primary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp
    )
}

/** Horizontal strip of preset cards. */
@Composable
private fun PresetRow(
    presets: List<ColorPalette>,
    activeId: String,
    onApply: (String) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(presets) { p ->
            PresetCard(
                palette = p,
                isActive = p.id == activeId,
                onClick = { onApply(p.id) }
            )
        }
    }
}

/** A small card previewing a built-in preset. */
@Composable
private fun PresetCard(
    palette: ColorPalette,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val adaptive = LocalAdaptiveMetrics.current
    val border = if (isActive) GlobalColors.primary else TitanColors.AbsoluteWhite.copy(alpha = 0.15f)
    Column(
        modifier = Modifier
            .width(if (adaptive.isCompact) 104.dp else 126.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(GlobalColors.primary.copy(alpha = 0.10f))
            .border(if (isActive) 2.dp else 1.dp, border, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Four-dot swatch
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(palette.primary, palette.secondary, palette.tertiary, palette.quaternary).forEach { slot ->
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(slot.base)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            palette.name,
            color = TitanColors.AbsoluteWhite,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun SaveButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(GlobalColors.primary.copy(alpha = 0.2f))
            .border(1.dp, GlobalColors.primary, RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Save,
            contentDescription = "Save palette",
            tint = GlobalColors.primary
        )
    }
}

@Composable
private fun SavedPaletteRow(
    palette: ColorPalette,
    isActive: Boolean,
    onApply: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(GlobalColors.primary.copy(alpha = 0.08f))
            .border(
                1.dp,
                if (isActive) GlobalColors.primary else TitanColors.AbsoluteWhite.copy(alpha = 0.1f),
                RoundedCornerShape(8.dp)
            )
            .clickable { onApply() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            listOf(palette.primary, palette.secondary, palette.tertiary, palette.quaternary).forEach { slot ->
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(slot.base)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            palette.name,
            color = TitanColors.AbsoluteWhite,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete",
                tint = TitanColors.AbsoluteWhite.copy(alpha = 0.4f)
            )
        }
    }
}

/** Look up a slot by name in a palette (case-insensitive). */
private fun slotByName(palette: ColorPalette, name: String) = when (name.uppercase()) {
    "PRIMARY" -> palette.primary
    "SECONDARY" -> palette.secondary
    "TERTIARY" -> palette.tertiary
    "QUATERNARY" -> palette.quaternary
    "ACCENT" -> palette.accent
    else -> palette.primary
}
