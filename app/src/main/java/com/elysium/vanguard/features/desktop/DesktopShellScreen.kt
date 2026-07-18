package com.elysium.vanguard.features.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.elysium.vanguard.features.desktop.model.DesktopSessionState
import com.elysium.vanguard.features.desktop.model.DesktopWindow
import com.elysium.vanguard.features.desktop.model.WindowState

/**
 * The Universal Desktop Shell — the host
 * composable that renders the desktop, the
 * windows, and the dock.
 *
 * Phase 1 ships a **placeholder** composable:
 *   - The desktop background is a dark color.
 *   - The list of windows is rendered as a
 *     text list (for debug + for the JVM
 *     preview).
 *   - The dock is rendered as a row of items at
 *     the bottom of the screen.
 *
 * Phase 2 replaces the placeholder text with a
 * real Compose windowing implementation (with
 * draggable window frames, click-to-focus, etc.).
 * The state machine + the ViewModel are stable;
 * only the composable changes.
 *
 * Why Phase 1 is a placeholder: the Compose
 * `androidx.compose.ui` windowing primitives are
 * still experimental in the Compose 1.5.x line
 * (used by this project). The Phase 1 placeholder
 * is the **integration** — it proves the
 * ViewModel + the state machine work end-to-end
 * without depending on the windowing primitives.
 */
@Composable
fun DesktopShellScreen(viewModel: DesktopShellViewModel) {
    val state by viewModel.state.collectAsState()
    DesktopShellContent(state = state)
}

@Composable
private fun DesktopShellContent(state: DesktopSessionState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Desktop area: render the windows as a
        // text list. Phase 2 replaces this with
        // real Compose windows.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp),
        ) {
            Column {
                Text(
                    text = "Elysium Vanguard Desktop",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = "Open windows: ${state.windows.size} | Dock: ${state.dockItems.size} items",
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.windows.forEach { window ->
                    Text(
                        text = "- ${window.title} (${window.state.name}, z=${window.zOrder})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Dock(state = state)
    }
}

@Composable
private fun Dock(state: DesktopSessionState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(Color.DarkGray)
            .padding(8.dp),
    ) {
        Text(
            text = state.dockItems.joinToString(separator = "  ") { it.label },
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Preview helper for the JVM preview tool. The
 * preview renders a sample session with 2
 * windows + 3 dock items.
 */
@Composable
fun DesktopShellScreenPreview() {
    val sampleState = DesktopSessionState(
        windows = listOf(
            DesktopWindow(
                id = "app-1",
                title = "Terminal",
                iconKey = "terminal",
                state = WindowState.NORMAL,
                bounds = com.elysium.vanguard.features.desktop.model.WindowBounds(100, 100, 800, 600),
                zOrder = 1,
                lastInteractionAt = 0L,
            ),
            DesktopWindow(
                id = "app-2",
                title = "Files",
                iconKey = "files",
                state = WindowState.MINIMIZED,
                bounds = com.elysium.vanguard.features.desktop.model.WindowBounds(200, 200, 800, 600),
                zOrder = 2,
                lastInteractionAt = 0L,
            ),
        ),
        focusedWindowId = "app-1",
        dockItems = listOf(
            com.elysium.vanguard.features.desktop.model.DockItem(
                iconKey = "terminal",
                label = "Terminal",
                kind = com.elysium.vanguard.features.desktop.model.DockItemKind.RUNNING_WINDOW,
                windowId = "app-1",
            ),
            com.elysium.vanguard.features.desktop.model.DockItem(
                iconKey = "files",
                label = "Files",
                kind = com.elysium.vanguard.features.desktop.model.DockItemKind.RUNNING_WINDOW,
                windowId = "app-2",
            ),
            com.elysium.vanguard.features.desktop.model.DockItem(
                iconKey = "browser",
                label = "Browser",
                kind = com.elysium.vanguard.features.desktop.model.DockItemKind.PINNED_APP,
                windowId = null,
            ),
        ),
        desktopBounds = com.elysium.vanguard.features.desktop.model.WindowBounds(0, 0, 1920, 1080),
    )
    DesktopShellContent(state = sampleState)
}
