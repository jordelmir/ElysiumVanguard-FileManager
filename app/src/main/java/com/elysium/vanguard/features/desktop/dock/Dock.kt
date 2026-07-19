package com.elysium.vanguard.features.desktop.dock

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.elysium.vanguard.features.desktop.content.WindowContentRegistry
import com.elysium.vanguard.features.desktop.model.DockItem
import com.elysium.vanguard.features.desktop.model.DockItemKind

/**
 * Phase 78 — the **real** dock (taskbar
 * at the bottom of the desktop).
 *
 * Phase 1 rendered the dock as a single
 * line of `Text`. Phase 78 ships a real
 * taskbar with:
 *
 * - A horizontal `LazyRow` of items
 *   (pinned apps + running windows).
 * - Each item shows the app's icon (from
 *   the [WindowContentRegistry]) + a
 *   small **running indicator** (a 4dp
 *   dot below the icon) when the item
 *   is a `RUNNING_WINDOW`.
 * - The item is clickable: a
 *   `RUNNING_WINDOW` click focuses the
 *   window (or restores it from
 *   minimized); a `PINNED_APP` click
 *   launches the app (opens a new
 *   window).
 * - The item is dim when its window is
 *   not the focused window; bright
 *   when it is.
 * - A subtle background gradient so the
 *   dock is visually distinct from the
 *   desktop area.
 */
@Composable
fun Dock(
    items: List<DockItem>,
    focusedWindowId: String?,
    onItemClick: (DockItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(items, key = { it.iconKey + ":" + (it.windowId ?: "pinned") }) { item ->
                DockItemView(
                    item = item,
                    isFocused = item.kind == DockItemKind.RUNNING_WINDOW &&
                        item.windowId == focusedWindowId,
                    onClick = { onItemClick(item) },
                )
            }
        }
    }
}

/**
 * A single dock item: icon + running
 * indicator. The label is shown on
 * long-press / accessibility (Phase 2).
 */
@Composable
private fun DockItemView(
    item: DockItem,
    isFocused: Boolean,
    onClick: () -> Unit,
) {
    val content = WindowContentRegistry.resolve(item.iconKey)
    val iconTint = if (isFocused) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = content.icon,
                contentDescription = item.label,
                modifier = Modifier.size(24.dp),
                tint = iconTint,
            )
        }
        // Running indicator: a 4dp dot
        // below the icon. Visible when
        // the item is a RUNNING_WINDOW;
        // invisible (zero-size) for
        // PINNED_APP.
        if (item.kind == DockItemKind.RUNNING_WINDOW) {
            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(width = 16.dp, height = 3.dp)
                    .clip(CircleShape)
                    .background(
                        if (isFocused) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        }
                    ),
            )
        } else {
            // Spacer to keep the layout
            // consistent (otherwise the
            // icon shifts up when the
            // indicator is absent).
            Box(modifier = Modifier.size(width = 16.dp, height = 5.dp))
        }
    }
}

/**
 * A small text label for the dock
 * (e.g. a clock, a system status).
 * Reserved for Phase 79+; shipped
 * here as a helper so the dock layout
 * is complete.
 */
@Composable
fun DockStatusBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
