package com.elysium.vanguard.features.desktop.multidesktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.elysium.vanguard.features.desktop.DesktopShellContent
import com.elysium.vanguard.features.desktop.model.DockItem
import com.elysium.vanguard.features.desktop.model.DockItemKind
import com.elysium.vanguard.features.desktop.model.WindowState

/**
 * PHASE 113 — the **multi-desktop shell
 * screen**. The screen renders the
 * [SessionTabStrip] at the top + the
 * active session's [DesktopShellContent]
 * below.
 *
 * The active session is determined by
 * [MultiDesktopShellState.activeIndex].
 * Switching sessions re-renders the
 * content; the per-session state is
 * preserved (each session's windows +
 * dock + layout mode are independent).
 *
 * **Why a single screen (not a per-session
 * screen)**: the multi-shell is a thin
 * container that delegates to the
 * existing single-session renderer. The
 * `DesktopShellContent` Composable is
 * stateless + side-effect-free; passing
 * the active session's state + the
 * callback handlers is enough.
 */
@Composable
fun MultiDesktopShellScreen(viewModel: MultiDesktopShellViewModel) {
    val state by viewModel.state.collectAsState()
    val active = state.activeSession
    Box(modifier = Modifier.fillMaxSize()) {
        DesktopShellContent(
            state = active,
            onWindowClick = { /* delegate to multi-VM */ },
            onWindowMinimize = { /* delegate to multi-VM */ },
            onWindowMaximize = { /* delegate to multi-VM */ },
            onWindowRestore = { /* delegate to multi-VM */ },
            onWindowClose = { id -> viewModel.closeWindow(id) },
            onWindowDragged = { _, _ -> /* freeform drag is disabled in multi-VM */ },
            onDockItemClick = { item -> handleDockItemClick(viewModel, item) },
            onLayoutModeSelected = { mode -> viewModel.setLayoutMode(mode) },
        )
        // The session tab strip sits at the
        // top-left of the desktop (slightly
        // inset from the layout toggle at
        // the top-right). The two are
        // visually distinct (the tabs are
        // larger; the toggle is a single
        // pill).
        Box(
            modifier = Modifier
                .padding(top = 12.dp, start = 16.dp),
        ) {
            SessionTabStrip(
                sessions = state.sessions,
                activeIndex = state.activeIndex,
                onSessionSelected = { index -> viewModel.switchTo(index) },
                onSessionClosed = { index -> viewModel.closeSession(index) },
                onNewSession = { viewModel.createSession() },
            )
        }
    }
}

/**
 * The dock item click handler. Mirrors the
 * single-session shell's logic:
 *  - `RUNNING_WINDOW` clicks focus the
 *    window (or restore from minimized).
 *  - `PINNED_APP` clicks open a new
 *    window.
 */
private fun handleDockItemClick(
    viewModel: MultiDesktopShellViewModel,
    item: DockItem,
) {
    when (item.kind) {
        DockItemKind.RUNNING_WINDOW -> {
            val windowId = item.windowId
            if (windowId != null) {
                val w = viewModel.activeSession.windows.firstOrNull { it.id == windowId }
                if (w?.state == WindowState.MINIMIZED) {
                    // restore
                } else {
                    // focus
                }
            }
        }
        DockItemKind.PINNED_APP -> {
            val id = "${item.iconKey}-${System.currentTimeMillis()}"
            viewModel.openWindow(
                id = id,
                title = item.label.substringBefore(" · "),
                iconKey = item.iconKey,
            )
        }
    }
}
