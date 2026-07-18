package com.elysium.vanguard.features.desktop.model

/**
 * A typed reference to a single desktop window. The
 * window is a **passive value** — the state machine
 * is enforced by the `DesktopShellViewModel`, not
 * by the window itself.
 *
 * The window carries:
 *   - `id`: a unique id (the Market listing id of
 *     the app, when the window is hosting a Market
 *     app).
 *   - `title`: the window title (the app name, when
 *     the window is hosting a Market app).
 *   - `iconKey`: a stable key for the app's icon
 *     (used by the dock + the launcher).
 *   - `state`: the current state
 *     (NORMAL / MINIMIZED / MAXIMIZED).
 *   - `bounds`: the window's bounds (x, y, width,
 *     height). For `MAXIMIZED` windows, the bounds
 *     are the desktop bounds.
 *   - `zOrder`: the z-order (higher = on top).
 *     The focused window is always on top.
 *   - `lastInteractionAt`: the timestamp of the
 *     last user interaction (for the focus
 *     heuristic).
 */
data class DesktopWindow(
    val id: String,
    val title: String,
    val iconKey: String,
    val state: WindowState,
    val bounds: WindowBounds,
    val zOrder: Int,
    val lastInteractionAt: Long,
) {
    init {
        require(id.isNotBlank()) { "DesktopWindow id must not be blank" }
        require(title.isNotBlank()) { "DesktopWindow title must not be blank" }
        require(iconKey.isNotBlank()) { "DesktopWindow iconKey must not be blank" }
        require(bounds.width >= 0) { "DesktopWindow width must be non-negative" }
        require(bounds.height >= 0) { "DesktopWindow height must be non-negative" }
    }
}

/**
 * The bounds of a window. Phase 1 uses integer
 * coordinates (pixels). Phase 2 may switch to
 * `Dp` for Compose-native rendering.
 */
data class WindowBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)
