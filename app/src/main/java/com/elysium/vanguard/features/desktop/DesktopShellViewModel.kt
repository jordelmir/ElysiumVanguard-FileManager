package com.elysium.vanguard.features.desktop

import com.elysium.vanguard.features.desktop.model.DesktopSessionState
import com.elysium.vanguard.features.desktop.model.DesktopWindow
import com.elysium.vanguard.features.desktop.model.DockItem
import com.elysium.vanguard.features.desktop.model.DockItemKind
import com.elysium.vanguard.features.desktop.model.WindowBounds
import com.elysium.vanguard.features.desktop.model.WindowState
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp
import androidx.lifecycle.ViewModel
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
 * **Instantiation**: the ViewModel does NOT use
 * `@HiltViewModel` because the constructor
 * needs an explicit `MutableStateFlow` and
 * `clock` (the test seam). The Hilt graph
 * provides a `ViewModelProvider.Factory` (see
 * [DesktopShellViewModelFactory]) that creates
 * the production instance with
 * [defaultInitialState] + the platform's
 * monotonic wall clock. Tests instantiate the
 * ViewModel directly.
 */
open class DesktopShellViewModel(
    initialStateFlow: MutableStateFlow<DesktopSessionState>,
    private val clock: Timestamp.Companion.TimestampSource,
) : ViewModel() {
    private val _state: MutableStateFlow<DesktopSessionState> = initialStateFlow
    val state: StateFlow<DesktopSessionState> = _state.asStateFlow()

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
                return@update current
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

    fun closeWindow(id: String): Result<Unit> {
        _state.update { current ->
            if (current.windows.none { it.id == id }) {
                return@update current
            }
            current.copy(
                windows = current.windows.filterNot { it.id == id },
                focusedWindowId = if (current.focusedWindowId == id) {
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
        const val DEFAULT_DESKTOP_WIDTH = 1920
        const val DEFAULT_DESKTOP_HEIGHT = 1080

        fun defaultInitialState(): DesktopSessionState = DesktopSessionState(
            windows = emptyList(),
            focusedWindowId = null,
            dockItems = listOf(
                DockItem("terminal", "Terminal", DockItemKind.PINNED_APP, null),
                DockItem("files", "Files", DockItemKind.PINNED_APP, null),
                DockItem("settings", "Settings", DockItemKind.PINNED_APP, null),
                DockItem("notes", "Notes", DockItemKind.PINNED_APP, null),
            ),
            desktopBounds = WindowBounds(0, 0, DEFAULT_DESKTOP_WIDTH, DEFAULT_DESKTOP_HEIGHT),
        )
    }
}

/**
 * The factory Compose uses to instantiate the
 * [DesktopShellViewModel]. The factory
 * constructs the production default state
 * (4 pinned apps, 1920x1080 desktop bounds) +
 * the platform's monotonic wall clock.
 */
object DesktopShellViewModelFactory : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DesktopShellViewModel::class.java)) {
            "DesktopShellViewModelFactory cannot create $modelClass"
        }
        return DesktopShellViewModel(
            initialStateFlow = kotlinx.coroutines.flow.MutableStateFlow(
                DesktopShellViewModel.defaultInitialState()
            ),
            clock = com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp.monotonicWallClock(),
        ) as T
    }
}
