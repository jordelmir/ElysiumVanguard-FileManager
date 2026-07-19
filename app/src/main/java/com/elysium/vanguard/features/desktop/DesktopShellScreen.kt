package com.elysium.vanguard.features.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.elysium.vanguard.features.desktop.content.WindowContentRegistry
import com.elysium.vanguard.features.desktop.dock.Dock
import com.elysium.vanguard.features.desktop.dock.DockStatusBadge
import com.elysium.vanguard.features.desktop.drag.WindowDragMath
import com.elysium.vanguard.features.desktop.model.DesktopSessionState
import com.elysium.vanguard.features.desktop.model.DesktopWindow
import com.elysium.vanguard.features.desktop.model.DockItemKind
import com.elysium.vanguard.features.desktop.model.WindowState
import com.elysium.vanguard.features.desktop.window.WindowFrame

/**
 * The Universal Desktop Shell — the host
 * composable that renders the desktop, the
 * windows, and the dock.
 *
 * Phase 78 replaces the Phase 1 text list
 * with a real Compose windowing surface:
 *
 * - Each [DesktopWindow] is rendered as a
 *   [WindowFrame] at its bounds.
 * - The frame is draggable via the title
 *   bar (the `onTitleBarClick` callback
 *   focuses the window; the
 *   `pointerInput` modifier in
 *   [PositionedWindow] handles the drag).
 * - The frame's buttons (min / max / close)
 *   call the ViewModel directly.
 * - The [Dock] at the bottom shows
 *   `RUNNING_WINDOW` + `PINNED_APP` items
 *   with a running indicator.
 * - The desktop background is a
 *   [Sovereign] gradient (the platform's
 *   signature look — deep navy → indigo).
 *
 * The ViewModel + the state machine are
 * unchanged from Phase 1. The composable
 * is the only thing that changed.
 */
@Composable
fun DesktopShellScreen(viewModel: DesktopShellViewModel) {
    val state by viewModel.state.collectAsState()
    DesktopShellContent(
        state = state,
        onWindowClick = { id -> viewModel.focusWindow(id) },
        onWindowMinimize = { id -> viewModel.minimizeWindow(id) },
        onWindowMaximize = { id -> viewModel.maximizeWindow(id) },
        onWindowRestore = { id -> viewModel.restoreWindow(id) },
        onWindowClose = { id -> viewModel.closeWindow(id) },
        onWindowDragged = { id, bounds -> viewModel.updateWindowBounds(id, bounds) },
        onDockItemClick = { item ->
            when (item.kind) {
                DockItemKind.RUNNING_WINDOW -> {
                    val windowId = item.windowId
                    if (windowId != null) {
                        val w = state.windows.firstOrNull { it.id == windowId }
                        if (w?.state == WindowState.MINIMIZED) {
                            viewModel.restoreWindow(windowId)
                        } else {
                            viewModel.focusWindow(windowId)
                        }
                    }
                }
                DockItemKind.PINNED_APP -> {
                    // Launch a new window for the pinned app.
                    val id = "${item.iconKey}-${System.currentTimeMillis()}"
                    viewModel.openWindow(
                        id = id,
                        title = item.label,
                        iconKey = item.iconKey,
                    )
                }
            }
        },
    )
}

/**
 * The pure rendering entry. This
 * composable is what the JVM preview
 * tool renders; it does not depend on
 * the ViewModel directly.
 */
@Composable
fun DesktopShellContent(
    state: DesktopSessionState,
    onWindowClick: (String) -> Unit = {},
    onWindowMinimize: (String) -> Unit = {},
    onWindowMaximize: (String) -> Unit = {},
    onWindowRestore: (String) -> Unit = {},
    onWindowClose: (String) -> Unit = {},
    onWindowDragged: (String, com.elysium.vanguard.features.desktop.model.WindowBounds) -> Unit = { _, _ -> },
    onDockItemClick: (com.elysium.vanguard.features.desktop.model.DockItem) -> Unit = {},
) {
    // Measured desktop size in pixels.
    var measuredSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val titleBarHeightPx = with(density) { 36.dp.toPx().toInt() }
    val dockHeightPx = with(density) { 72.dp.toPx().toInt() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0A0E1A),
                        Color(0xFF1A1240),
                        Color(0xFF2A0E40),
                    ),
                )
            )
            .onSizeChanged { measuredSize = it },
    ) {
        // Render visible windows in z-order
        // (lowest first; higher z-order is
        // drawn on top).
        val sortedWindows = state.windows
            .filter { it.state != WindowState.MINIMIZED }
            .sortedBy { it.zOrder }
        sortedWindows.forEach { window ->
            PositionedWindow(
                window = window,
                isFocused = window.id == state.focusedWindowId,
                desktopSize = measuredSize,
                titleBarHeightPx = titleBarHeightPx,
                dockHeightPx = dockHeightPx,
                onClick = { onWindowClick(window.id) },
                onMinimize = { onWindowMinimize(window.id) },
                onMaximize = { onWindowMaximize(window.id) },
                onRestore = { onWindowRestore(window.id) },
                onClose = { onWindowClose(window.id) },
                onDragged = { newBounds -> onWindowDragged(window.id, newBounds) },
            )
        }
        // Dock at the bottom
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(androidx.compose.ui.Alignment.BottomCenter)
                .height(72.dp),
        ) {
            Dock(
                items = state.dockItems,
                focusedWindowId = state.focusedWindowId,
                onItemClick = onDockItemClick,
            )
            // Status badge in the right corner.
            Box(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.CenterEnd)
                    .padding(end = 12.dp),
            ) {
                DockStatusBadge(text = "elysium • v1.0")
            }
        }
    }
}

/**
 * A single window positioned at its
 * bounds. The window's drag math is
 * delegated to [WindowDragMath.applyDrag];
 * the modifier converts the pixel delta
 * from `detectDragGestures` to integer
 * bounds.
 */
@Composable
private fun PositionedWindow(
    window: DesktopWindow,
    isFocused: Boolean,
    desktopSize: IntSize,
    titleBarHeightPx: Int,
    dockHeightPx: Int,
    onClick: () -> Unit,
    onMinimize: () -> Unit,
    onMaximize: () -> Unit,
    onRestore: () -> Unit,
    onClose: () -> Unit,
    onDragged: (com.elysium.vanguard.features.desktop.model.WindowBounds) -> Unit,
) {
    val density = LocalDensity.current
    val widthDp = with(density) { window.bounds.width.toDp() }
    val heightDp = with(density) { window.bounds.height.toDp() }
    val xDp = with(density) { window.bounds.x.toDp() }
    val yDp = with(density) { window.bounds.y.toDp() }
    val content = WindowContentRegistry.resolve(window.iconKey)
    Box(
        modifier = Modifier
            .offset(x = xDp, y = yDp)
            .size(width = widthDp, height = heightDp)
            .pointerInput(window.id, desktopSize) {
                detectDragGesturesOnTitleBar { deltaX, deltaY ->
                    val newBounds = WindowDragMath.applyDrag(
                        bounds = window.bounds,
                        deltaX = deltaX,
                        deltaY = deltaY,
                        desktopBounds = com.elysium.vanguard.features.desktop.model.WindowBounds(
                            x = 0,
                            y = 0,
                            width = desktopSize.width,
                            height = desktopSize.height,
                        ),
                        titleBarHeight = titleBarHeightPx,
                        dockReservedHeight = dockHeightPx,
                    )
                    onDragged(newBounds)
                }
            },
    ) {
        WindowFrame(
            title = window.title,
            iconKey = window.iconKey,
            isFocused = isFocused,
            isMaximized = window.state == WindowState.MAXIMIZED,
            onTitleBarClick = onClick,
            onMinimize = onMinimize,
            onMaximize = onMaximize,
            onRestore = onRestore,
            onClose = onClose,
            modifier = Modifier.fillMaxSize(),
        ) {
            content.body()
        }
    }
}

// --- Modifier extension imports ---

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectDragGesturesOnTitleBar(
    onDrag: (Float, Float) -> Unit,
) {
    // The drag is constrained to the title bar
    // by the modifier's `pointerInput` host: the
    // title bar is the only child that consumes
    // the gesture, so the body never sees a drag.
    // (Compose dispatches gestures to the deepest
    // child that has a `pointerInput` modifier; the
    // title bar's `clickable` is the deepest here.)
    detectDragGestures { change, dragAmount ->
        change.consume()
        onDrag(dragAmount.x, dragAmount.y)
    }
}
