package com.elysium.vanguard.features.desktop.model

/**
 * The state of a single desktop window. The state
 * machine is:
 *   NORMAL --minimize--> MINIMIZED
 *   NORMAL --maximize--> MAXIMIZED
 *   MINIMIZED --restore--> NORMAL
 *   MAXIMIZED --restore--> NORMAL
 *   {any} --close--> (removed)
 *
 * The state machine is enforced by the
 * `DesktopShellViewModel.transition` method; the
 * window data class is a passive value.
 */
enum class WindowState {
    /** The window is in the normal position + size. */
    NORMAL,

    /** The window is minimized (in the dock). */
    MINIMIZED,

    /** The window is maximized (full desktop). */
    MAXIMIZED,
}
