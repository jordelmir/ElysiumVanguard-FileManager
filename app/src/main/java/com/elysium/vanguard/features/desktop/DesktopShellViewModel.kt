package com.elysium.vanguard.features.desktop

import com.elysium.vanguard.features.desktop.model.DesktopSessionState
import com.elysium.vanguard.features.desktop.model.DesktopWindow
import com.elysium.vanguard.features.desktop.model.DockItem
import com.elysium.vanguard.features.desktop.model.DockItemKind
import com.elysium.vanguard.features.desktop.model.WindowBounds
import com.elysium.vanguard.features.desktop.model.WindowState
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The `DesktopShellViewModel` is the **only
 * legitimate way** to mutate the desktop session
 * state. The ViewModel enforces the window state
 * machine + the focus invariant + the z-order
 * invariant.
 *
 * Invariants enforced by the ViewModel:
 *   - The focused window is always on top
 *     (highest zOrder).
 *   - The zOrder is monotonically increasing
 *     (no two windows share a zOrder).
 *   - A `MAXIMIZED` window's bounds are the
 *     desktop bounds.
 *   - Closing a window removes it from the
 *     windows list AND removes the
 *     corresponding `RUNNING_WINDOW` dock
 *     item.
 *
 * The session state is exposed as a `StateFlow`
 * for Compose consumption.
 */
class DesktopShellViewModel(
    initialState: DesktopSessionState,
    private val clock: Timestamp.Companion.TimestampSource = Timestamp.monotonicWallClock(),
) {

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<DesktopSessionState> = _state.asStateFlow()

    /**
     * Open a new window. The new window is focused
     * (on top of the z-order). The new window's
     * bounds are a default centered rect.
     */
    fun openWindow(
        id: String,
        title: String,
        iconKey: String,
        defaultWidth: Int = DEFAULT_WINDOW_WIDTH,
        defaultHeight: Int = DEFAULT_WINDOW_HEIGHT,
    ): Result<Unit> {
        if (id.isBlank()) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "DesktopWindow.id",
                    reason = "id must not be blank",
                ),
            )
        }
        _state.update { current ->
            if (current.windows.any { it.id == id }) {
                return@update current // no-op if window already open
            }
            val newZOrder = current.nextZOrder
            val centeredX = (current.desktopBounds.width - defaultWidth) / 2
            val centeredY = (current.desktopBounds.height - defaultHeight) / 2
            val newWindow = DesktopWindow(
                id = id,
                title = title,
                iconKey = iconKey,
                state = WindowState.NORMAL,
                bounds = WindowBounds(
                    x = centeredX.coerceAtLeast(0),
                    y = centeredY.coerceAtLeast(0),
                    width = defaultWidth,
                    height = defaultHeight,
                ),
                zOrder = newZOrder,
                lastInteractionAt = clock.now().epochMs,
            )
            current.copy(
                windows = current.windows + newWindow,
                focusedWindowId = id,
                dockItems = current.dockItems + DockItem(
                    iconKey = iconKey,
                    label = title,
                    kind = DockItemKind.RUNNING_WINDOW,
                    windowId = id,
                ),
                nextZOrder = newZOrder + 1,
            )
        }
        return Result.success(Unit)
    }

    /**
     * Close a window. The window is removed from
     * the windows list + the corresponding
     * `RUNNING_WINDOW` dock item is removed.
     */
    fun closeWindow(id: String): Result<Unit> {
        _state.update { current ->
            if (current.windows.none { it.id == id }) {
                return@update current
            }
            current.copy(
                windows = current.windows.filterNot { it.id == id },
                focusedWindowId = if (current.focusedWindowId == id) {
                    // Focus the top-most remaining window
                    current.windows
                        .filterNot { it.id == id }
                        .maxByOrNull { it.zOrder }
                        ?.id
                } else {
                    current.focusedWindowId
                },
                dockItems = current.dockItems.filterNot {
                    it.kind == DockItemKind.RUNNING_WINDOW && it.windowId == id
                },
            )
        }
        return Result.success(Unit)
    }

    /**
     * Focus a window. The window is brought to the
     * top (highest zOrder). If the window was
     * minimized, it's restored to NORMAL.
     */
    fun focusWindow(id: String): Result<Unit> {
        _state.update { current ->
            val target = current.windows.firstOrNull { it.id == id }
                ?: return@update current
            val newZOrder = current.nextZOrder
            val updated = target.copy(
                state = WindowState.NORMAL,
                zOrder = newZOrder,
                lastInteractionAt = clock.now().epochMs,
            )
            current.copy(
                windows = current.windows.map { if (it.id == id) updated else it },
                focusedWindowId = id,
                nextZOrder = newZOrder + 1,
            )
        }
        return Result.success(Unit)
    }

    /**
     * Minimize a window. The window's state is set
     * to MINIMIZED; the focused window is
     * unchanged (or moves to the next visible
     * window).
     */
    fun minimizeWindow(id: String): Result<Unit> {
        _state.update { current ->
            val target = current.windows.firstOrNull { it.id == id }
                ?: return@update current
            val updated = target.copy(
                state = WindowState.MINIMIZED,
                lastInteractionAt = clock.now().epochMs,
            )
            current.copy(
                windows = current.windows.map { if (it.id == id) updated else it },
                focusedWindowId = if (current.focusedWindowId == id) {
                    current.windows
                        .filterNot { it.id == id }
                        .filter { it.state != WindowState.MINIMIZED }
                        .maxByOrNull { it.zOrder }
                        ?.id
                } else {
                    current.focusedWindowId
                },
            )
        }
        return Result.success(Unit)
    }

    /**
     * Maximize a window. The window's state is set
     * to MAXIMIZED; the bounds are set to the
     * desktop bounds.
     */
    fun maximizeWindow(id: String): Result<Unit> {
        _state.update { current ->
            val target = current.windows.firstOrNull { it.id == id }
                ?: return@update current
            val newZOrder = current.nextZOrder
            val updated = target.copy(
                state = WindowState.MAXIMIZED,
                bounds = current.desktopBounds,
                zOrder = newZOrder,
                lastInteractionAt = clock.now().epochMs,
            )
            current.copy(
                windows = current.windows.map { if (it.id == id) updated else it },
                focusedWindowId = id,
                nextZOrder = newZOrder + 1,
            )
        }
        return Result.success(Unit)
    }

    /**
     * Restore a window from MAXIMIZED or MINIMIZED
     * to NORMAL. The bounds are NOT changed (the
     * previous NORMAL bounds are preserved).
     */
    fun restoreWindow(id: String): Result<Unit> {
        _state.update { current ->
            val target = current.windows.firstOrNull { it.id == id }
                ?: return@update current
            val updated = target.copy(
                state = WindowState.NORMAL,
                lastInteractionAt = clock.now().epochMs,
            )
            current.copy(
                windows = current.windows.map { if (it.id == id) updated else it },
                focusedWindowId = id,
            )
        }
        return Result.success(Unit)
    }

    /**
     * Phase 78 — update a window's bounds.
     * Called by the desktop shell's drag
     * modifier when the user drags a
     * window's title bar. The new bounds
     * are clamped by [WindowDragMath.applyDrag]
     * before this method is called; the
     * ViewModel just persists the result.
     *
     * The bounds are updated for the
     * `NORMAL` state only. `MAXIMIZED`
     * windows ignore the update (the
     * desktop bounds are the only valid
     * position when maximized). `MINIMIZED`
     * windows ignore the update too (a
     * minimized window has no visible
     * position; the dock is its
     * position).
     */
    fun updateWindowBounds(id: String, newBounds: WindowBounds): Result<Unit> {
        _state.update { current ->
            val target = current.windows.firstOrNull { it.id == id }
                ?: return@update current
            if (target.state != WindowState.NORMAL) {
                return@update current
            }
            val updated = target.copy(
                bounds = newBounds,
                lastInteractionAt = clock.now().epochMs,
            )
            current.copy(
                windows = current.windows.map { if (it.id == id) updated else it },
            )
        }
        return Result.success(Unit)
    }

    /**
     * Pin an app to the dock. The app is added as
     * a `PINNED_APP` dock item. If the app is
     * already pinned, the call is a no-op.
     */
    fun pinApp(iconKey: String, label: String): Result<Unit> {
        if (iconKey.isBlank() || label.isBlank()) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "DockItem",
                    reason = "iconKey and label must not be blank",
                ),
            )
        }
        _state.update { current ->
            if (current.dockItems.any { it.iconKey == iconKey && it.kind == DockItemKind.PINNED_APP }) {
                return@update current
            }
            current.copy(
                dockItems = current.dockItems + DockItem(
                    iconKey = iconKey,
                    label = label,
                    kind = DockItemKind.PINNED_APP,
                    windowId = null,
                ),
            )
        }
        return Result.success(Unit)
    }

    companion object {
        const val DEFAULT_WINDOW_WIDTH = 800
        const val DEFAULT_WINDOW_HEIGHT = 600
    }
}
