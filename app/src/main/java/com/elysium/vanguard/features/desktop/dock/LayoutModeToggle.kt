package com.elysium.vanguard.features.desktop.dock

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.Window
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.elysium.vanguard.features.desktop.layout.LayoutMode

/**
 * PHASE 112 — the **layout mode toggle** in
 * the dock area.
 *
 * The toggle is a row of 3 small chips
 * (Freeform / Horizontal / Vertical). The
 * current mode is highlighted in the
 * primary color; the others sit at 40%
 * alpha. Clicking a chip switches the
 * desktop's layout mode.
 *
 * **Why a row of 3 chips (not a single
 * button that cycles)**: the user can see
 * all 3 modes at a glance + tap the one
 * they want directly. A "cycle" button
 * would require 2-3 taps to reach a
 * specific mode.
 *
 * **Why an icon + a label**: the icons
 * (Window / GridView / ViewAgenda) are
 * the conventional glyphs for free-form,
 * split-horizontal, and split-vertical
 * layouts (also used in Android, macOS,
 * Windows). The label is for screen
 * readers.
 */
@Composable
fun LayoutModeToggle(
    currentMode: LayoutMode,
    onModeSelected: (LayoutMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(
                color = Color(0xFF0A0E1A).copy(alpha = 0.55f),
                shape = RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ModeChip(
            mode = LayoutMode.FREEFORM,
            icon = Icons.Filled.Window,
            label = "Free",
            isSelected = currentMode == LayoutMode.FREEFORM,
            onClick = onModeSelected,
        )
        ModeChip(
            mode = LayoutMode.SPLIT_HORIZONTAL,
            icon = Icons.Filled.GridView,
            label = "Split",
            isSelected = currentMode == LayoutMode.SPLIT_HORIZONTAL,
            onClick = onModeSelected,
        )
        ModeChip(
            mode = LayoutMode.SPLIT_VERTICAL,
            icon = Icons.Filled.ViewAgenda,
            label = "Stack",
            isSelected = currentMode == LayoutMode.SPLIT_VERTICAL,
            onClick = onModeSelected,
        )
    }
}

@Composable
private fun ModeChip(
    mode: LayoutMode,
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: (LayoutMode) -> Unit,
) {
    val backgroundAlpha = if (isSelected) 0.9f else 0.35f
    val tint = if (isSelected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        Color.White.copy(alpha = 0.7f)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.White.copy(alpha = backgroundAlpha * 0.1f)
                },
            )
            .clickable { onClick(mode) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                color = tint,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
