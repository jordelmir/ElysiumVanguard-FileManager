package com.elysium.vanguard.features.desktop.multidesktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.elysium.vanguard.features.desktop.model.DesktopSessionState

/**
 * PHASE 113 — the **session tab strip** at
 * the top of the multi-desktop shell.
 *
 * The strip is a row of tabs — one per
 * session — plus a "+ New" button. The
 * active session's tab is highlighted in
 * the primary color; the others sit at
 * 30% alpha. Each tab has a close (×)
 * button that closes the session.
 *
 * **Why a tab strip (not a list)**: a tab
 * strip is the conventional UI for
 * multi-document / multi-space apps
 * (browsers, code editors, OS desktops).
 * The user sees all sessions at a glance +
 * can switch with a single click.
 *
 * **Why an "Add" button at the end (not a
 * menu)**: a single "+" button is the
 * standard affordance; the user clicks it
 * to create a new session.
 *
 * **Why the close button on each tab (not
 * only on the active tab)**: the user
 * frequently wants to close a session
 * without first activating it (e.g.
 * "discard this scratch session").
 */
@Composable
fun SessionTabStrip(
    sessions: List<DesktopSessionState>,
    activeIndex: Int,
    onSessionSelected: (Int) -> Unit,
    onSessionClosed: (Int) -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(
                color = Color(0xFF0A0E1A).copy(alpha = 0.55f),
                shape = RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        sessions.forEachIndexed { index, session ->
            val isActive = index == activeIndex
            val title = sessionTabTitle(session, index)
            SessionTab(
                title = title,
                isActive = isActive,
                windowCount = session.windows.size,
                canClose = sessions.size > 1,
                onClick = { onSessionSelected(index) },
                onClose = { onSessionClosed(index) },
            )
        }
        // The "+" button to add a new
        // session.
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(
                    color = Color.White.copy(alpha = 0.10f),
                )
                .clickable { onNewSession() }
                .size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "New session",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun SessionTab(
    title: String,
    isActive: Boolean,
    windowCount: Int,
    canClose: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val backgroundColor = if (isActive) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.White.copy(alpha = 0.10f)
    }
    val textColor = if (isActive) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        Color.White.copy(alpha = 0.7f)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(color = backgroundColor)
            .clickable { onClick() }
            .padding(start = 12.dp, end = if (canClose) 4.dp else 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            color = textColor,
            style = MaterialTheme.typography.labelMedium,
        )
        // The window count badge — "N" with
        // a small dot, indicating how many
        // windows are open in the session.
        if (windowCount > 0) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(textColor.copy(alpha = 0.25f))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = windowCount.toString(),
                    color = textColor,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (canClose) {
            Spacer(modifier = Modifier.width(2.dp))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onClose() }
                    .size(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close session",
                    tint = textColor.copy(alpha = 0.7f),
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/**
 * The tab title. The first session is
 * "Default"; subsequent sessions are
 * "Session 1", "Session 2", etc. (the
 * "nextSessionNumber" the
 * [MultiDesktopShellViewModel] maintains
 * starts at 2, so the first auto-named
 * session is "Session 1"). The user's
 * custom name (if any) is the dock's
 * primary label; we derive the tab title
 * from the dock label or fall back to
 * "Session N".
 */
private fun sessionTabTitle(
    session: DesktopSessionState,
    index: Int,
): String {
    val firstDockItem = session.dockItems.firstOrNull()
    val label = firstDockItem?.label
    return when {
        !label.isNullOrBlank() && label.contains("·") -> {
            label.substringAfterLast("·").trim().ifBlank { "Session $index" }
        }
        !label.isNullOrBlank() -> label
        else -> "Session $index"
    }
}
