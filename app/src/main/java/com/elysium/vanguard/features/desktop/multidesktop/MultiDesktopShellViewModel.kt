package com.elysium.vanguard.features.desktop.multidesktop

import androidx.lifecycle.ViewModel
import com.elysium.vanguard.features.desktop.DesktopShellViewModel
import com.elysium.vanguard.features.desktop.model.DesktopSessionState
import com.elysium.vanguard.features.desktop.model.DesktopWindow
import com.elysium.vanguard.features.desktop.model.DockItemKind
import com.elysium.vanguard.features.desktop.model.WindowBounds
import com.elysium.vanguard.features.desktop.model.WindowState
import com.elysium.vanguard.features.desktop.layout.LayoutMode
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * PHASE 113 — the **multi-desktop shell
 * ViewModel**. The VM is the source of
 * mutations for the
 * [MultiDesktopShellState].
 *
 * The VM owns a list of
 * [DesktopSessionState]s (one per session /
 * space) + the active index. Every method
 * that mutates the active session delegates
 * to the same logic the single-session
 * [DesktopShellViewModel] uses; the
 * multi-shell is a thin wrapper that adds
 * the session-management surface.
 *
 * **Why a list of `DesktopSessionState`
 * (not a list of `DesktopShellViewModel`)**:
 * the multi-shell is a single ViewModel
 * (one entry in the `ViewModelStore`); the
 * `sessions` field is the list of passive
 * states. This avoids the complexity of
 * nesting ViewModels (which is not a
 * standard Compose pattern).
 *
 * **Why `MutableStateFlow<MultiDesktopShellState>`**:
 * the UI is a Compose hierarchy that
 * observes the state via `collectAsState`.
 * A `StateFlow` is the standard Compose-
 * friendly observable.
 *
 * **Thread-safety**: the state is
 * `MutableStateFlow` (thread-safe). The
 * `clock` is the test seam (the test
 * suite injects a deterministic clock).
 */
open class MultiDesktopShellViewModel(
    initialStateFlow: MutableStateFlow<MultiDesktopShellState>,
    private val clock: Timestamp.Companion.TimestampSource,
) : ViewModel() {

    private val _state: MutableStateFlow<MultiDesktopShellState> = initialStateFlow
    val state: StateFlow<MultiDesktopShellState> = _state.asStateFlow()

    /**
     * The active session's state. The
     * property is a view into the
     * `state.value.activeSession` — the
     * UI can pattern-match on the single
     * [DesktopSessionState] for rendering
     * the windows + the dock.
     */
    val activeSession: DesktopSessionState
        get() = _state.value.activeSession

    /**
     * Create a new session. The new
     * session starts with a fresh
     * [DesktopSessionState] (no windows,
     * the standard 4 pinned apps,
     * `FREEFORM` layout). The new session
     * becomes the active one.
     *
     * The [name] is optional — when null,
     * the VM auto-generates a "Session N"
     * name from the `nextSessionNumber`.
     */
    fun createSession(name: String? = null): Result<Int> {
        var newIndex = -1
        _state.update { current ->
            val resolvedName = name?.takeIf { it.isNotBlank() }
                ?: "Session ${current.nextSessionNumber}"
            val newSession = DesktopSessionState(
                windows = emptyList(),
                focusedWindowId = null,
                dockItems = defaultDockItems(resolvedName),
                desktopBounds = WindowBounds(
                    x = 0,
                    y = 0,
                    width = DesktopShellViewModel.DEFAULT_DESKTOP_WIDTH,
                    height = DesktopShellViewModel.DEFAULT_DESKTOP_HEIGHT,
                ),
            )
            newIndex = current.sessions.size
            current.copy(
                sessions = current.sessions + newSession,
                activeIndex = newIndex,
                nextSessionNumber = current.nextSessionNumber + 1,
            )
        }
        return if (newIndex >= 0) {
            Result.success(newIndex)
        } else {
            Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "MultiDesktopShellState",
                    reason = "createSession did not append a session",
                )
            )
        }
    }

    /**
     * Close a session at [index]. The
     * session is removed from the list.
     * The active index is adjusted: if
     * the closed session was the active
     * one, the new active index is the
     * one immediately to the right (or
     * the last remaining session if the
     * closed one was the last).
     *
     * The user cannot close the last
     * remaining session (a multi-desktop
     * shell must have at least one
     * session). The `Result.failure` is
     * a typed [FoundryError].
     */
    fun closeSession(index: Int): Result<Unit> {
        if (index < 0 || index >= _state.value.sessions.size) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "MultiDesktopShellState.sessions",
                    reason = "session index $index out of range",
                )
            )
        }
        if (_state.value.sessions.size <= 1) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "MultiDesktopShellState.sessions",
                    reason = "cannot close the last remaining session",
                )
            )
        }
        _state.update { current ->
            val newSessions = current.sessions.filterIndexed { i, _ -> i != index }
            val newActive = when {
                current.activeIndex == index -> {
                    // Closed the active session.
                    // Switch to the session at
                    // the same index (the one
                    // that took its place), or
                    // the last if we closed the
                    // last.
                    (index).coerceAtMost(newSessions.size - 1)
                }
                current.activeIndex > index -> {
                    // The active session was
                    // after the closed one;
                    // shift left.
                    current.activeIndex - 1
                }
                else -> current.activeIndex
            }
            current.copy(
                sessions = newSessions,
                activeIndex = newActive,
            )
        }
        return Result.success(Unit)
    }

    /**
     * Switch the active session to
     * [index]. The active session's
     * state is unchanged (the user can
     * switch back without losing data).
     */
    fun switchTo(index: Int): Result<Unit> {
        if (index < 0 || index >= _state.value.sessions.size) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "MultiDesktopShellState.sessions",
                    reason = "session index $index out of range",
                )
            )
        }
        if (index == _state.value.activeIndex) {
            return Result.success(Unit)
        }
        _state.update { current ->
            current.copy(activeIndex = index)
        }
        return Result.success(Unit)
    }

    /**
     * Open a window in the active session.
     * The method delegates to the same
     * logic the single-session ViewModel
     * uses: defaults for bounds, z-order,
     * dock item, etc.
     */
    fun openWindow(
        id: String,
        title: String,
        iconKey: String,
        defaultWidth: Int = DesktopShellViewModel.DEFAULT_WINDOW_WIDTH,
        defaultHeight: Int = DesktopShellViewModel.DEFAULT_WINDOW_HEIGHT,
    ): Result<Unit> {
        if (id.isBlank()) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "DesktopWindow.id",
                    reason = "id must not be blank",
                )
            )
        }
        _state.update { current ->
            val active = current.activeSession
            if (active.windows.any { it.id == id }) {
                return@update current
            }
            val newZOrder = active.nextZOrder
            val centeredX = (active.desktopBounds.width - defaultWidth) / 2
            val centeredY = (active.desktopBounds.height - defaultHeight) / 2
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
            val updatedActive = active.copy(
                windows = active.windows + newWindow,
                focusedWindowId = id,
                dockItems = active.dockItems + com.elysium.vanguard.features.desktop.model.DockItem(
                    iconKey = iconKey,
                    label = title,
                    kind = DockItemKind.RUNNING_WINDOW,
                    windowId = id,
                ),
                nextZOrder = newZOrder + 1,
            )
            current.copy(
                sessions = current.sessions.toMutableList().apply {
                    this[current.activeIndex] = updatedActive
                },
            )
        }
        return Result.success(Unit)
    }

    /**
     * Close a window in the active
     * session. The window is removed
     * from the session's list + the
     * session's focused id is updated.
     */
    fun closeWindow(id: String): Result<Unit> {
        _state.update { current ->
            val active = current.activeSession
            if (active.windows.none { it.id == id }) {
                return@update current
            }
            val updatedActive = active.copy(
                windows = active.windows.filterNot { it.id == id },
                focusedWindowId = if (active.focusedWindowId == id) {
                    active.windows
                        .filterNot { it.id == id }
                        .maxByOrNull { it.zOrder }
                        ?.id
                } else {
                    active.focusedWindowId
                },
                dockItems = active.dockItems.filterNot {
                    it.kind == DockItemKind.RUNNING_WINDOW && it.windowId == id
                },
            )
            current.copy(
                sessions = current.sessions.toMutableList().apply {
                    this[current.activeIndex] = updatedActive
                },
            )
        }
        return Result.success(Unit)
    }

    /**
     * Set the active session's layout
     * mode. Delegates to the
     * `setLayoutMode` logic on the
     * active session.
     */
    fun setLayoutMode(mode: LayoutMode): Result<Unit> {
        _state.update { current ->
            val active = current.activeSession
            val updatedActive = active.copy(layoutMode = mode)
            current.copy(
                sessions = current.sessions.toMutableList().apply {
                    this[current.activeIndex] = updatedActive
                },
            )
        }
        return Result.success(Unit)
    }

    /**
     * Build the default dock items for
     * a new session. The 4 pinned apps
     * match the single-session default
     * (terminal / files / settings /
     * notes); the session name is
     * appended to the labels so the
     * user can distinguish the docks
     * across sessions.
     */
    private fun defaultDockItems(sessionName: String): List<com.elysium.vanguard.features.desktop.model.DockItem> = listOf(
        com.elysium.vanguard.features.desktop.model.DockItem(
            iconKey = "terminal",
            label = "Terminal · $sessionName",
            kind = DockItemKind.PINNED_APP,
            windowId = null,
        ),
        com.elysium.vanguard.features.desktop.model.DockItem(
            iconKey = "files",
            label = "Files · $sessionName",
            kind = DockItemKind.PINNED_APP,
            windowId = null,
        ),
        com.elysium.vanguard.features.desktop.model.DockItem(
            iconKey = "settings",
            label = "Settings · $sessionName",
            kind = DockItemKind.PINNED_APP,
            windowId = null,
        ),
        com.elysium.vanguard.features.desktop.model.DockItem(
            iconKey = "notes",
            label = "Notes · $sessionName",
            kind = DockItemKind.PINNED_APP,
            windowId = null,
        ),
    )

    companion object {
        /**
         * The factory for the production
         * ViewModel. The factory builds a
         * fresh `MutableStateFlow` with the
         * default initial state + the
         * platform's monotonic wall clock.
         */
        object Factory : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(MultiDesktopShellViewModel::class.java)) {
                    "MultiDesktopShellViewModelFactory cannot create $modelClass"
                }
                return MultiDesktopShellViewModel(
                    initialStateFlow = MutableStateFlow(
                        MultiDesktopShellState.initial()
                    ),
                    clock = Timestamp.monotonicWallClock(),
                ) as T
            }
        }
    }
}
