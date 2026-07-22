package com.elysium.vanguard.features.desktop.model

import com.elysium.vanguard.features.desktop.layout.LayoutMode

/**
 * The session state of the Universal Desktop Shell.
 * The session is the **source of truth** for the
 * desktop: the list of windows + the focused
 * window + the dock items + the desktop bounds.
 *
 * The session is **append-only** for windows
 * (windows are opened, never replaced; a close
 * removes the window from the list, but the
 * session keeps a record of the closed window
 * for the `recently closed` affordance — Phase 2).
 */
data class DesktopSessionState(
    val windows: List<DesktopWindow>,
    val focusedWindowId: String?,
    val dockItems: List<DockItem>,
    val desktopBounds: WindowBounds,
    val nextZOrder: Int = INITIAL_Z_ORDER,
    /**
     * PHASE 112 — the current layout mode
     * ([LayoutMode.FREEFORM] by default).
     * The math that arranges the windows
     * is the
     * [com.elysium.vanguard.features.desktop.layout.WindowLayoutMath].
     * Switching modes is a ViewModel
     * action (the user toggles a button in
     * the dock or a settings panel).
     */
    val layoutMode: LayoutMode = LayoutMode.FREEFORM,
) {
    init {
        require(desktopBounds.width >= 0) { "desktopBounds width must be non-negative" }
        require(desktopBounds.height >= 0) { "desktopBounds height must be non-negative" }
        if (focusedWindowId != null) {
            require(windows.any { it.id == focusedWindowId }) {
                "focusedWindowId must reference an open window"
            }
        }
        windows.forEach { window ->
            require(window.zOrder in MIN_Z_ORDER..MAX_Z_ORDER) {
                "window zOrder ${window.zOrder} out of range [$MIN_Z_ORDER, $MAX_Z_ORDER]"
            }
        }
    }

    val focusedWindow: DesktopWindow?
        get() = windows.firstOrNull { it.id == focusedWindowId }

    val visibleWindows: List<DesktopWindow>
        get() = windows.filter { it.state != WindowState.MINIMIZED }

    companion object {
        const val INITIAL_Z_ORDER = 1
        const val MIN_Z_ORDER = 0
        const val MAX_Z_ORDER = Int.MAX_VALUE
    }
}
