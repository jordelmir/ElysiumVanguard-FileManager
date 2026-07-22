package com.elysium.vanguard.features.desktop.multidesktop

import com.elysium.vanguard.features.desktop.model.DesktopSessionState

/**
 * PHASE 113 — the **multi-desktop shell state**.
 *
 * The state is a list of [DesktopSessionState]s
 * (one per "session" / "space") + an
 * `activeIndex` pointing at the session the
 * user is currently looking at. The
 * multi-shell is the "spaces" feature (macOS)
 * / "virtual desktops" feature (Windows) /
 * "workspaces" feature (GNOME): the user can
 * have multiple independent desktops open at
 * once, each with its own windows + dock +
 * layout mode.
 *
 * **Why a list of `DesktopSessionState` (not
 * a list of `DesktopShellViewModel`)**: the
 * state is a passive value (the multi-shell
 * reads it from a `StateFlow`); the
 * ViewModel is the source of mutations. The
 * state carries the data; the ViewModel
 * carries the behavior.
 *
 * **Why an `activeIndex` (not an
 * `activeSessionId`)**: the sessions are
 * append-only; the user can `closeSession`
 * at an index, but the index of the
 * remaining sessions does not change. A
 * numerical index is the simplest way to
 * refer to the active session.
 *
 * **Why a `nextSessionNumber`**: the user
 * sees the session's name in the tab strip.
 * A auto-generated "Session 1", "Session 2"
 * default is the fallback when the user
 * doesn't pick a name.
 */
data class MultiDesktopShellState(
    val sessions: List<DesktopSessionState>,
    val activeIndex: Int,
    val nextSessionNumber: Int,
) {
    init {
        require(sessions.isNotEmpty()) {
            "MultiDesktopShellState must have at least one session"
        }
        require(activeIndex in sessions.indices) {
            "activeIndex $activeIndex out of range [0, ${sessions.size})"
        }
        require(nextSessionNumber >= 1) {
            "nextSessionNumber must be >= 1, got $nextSessionNumber"
        }
    }

    /**
     * The active session (the one the user
     * is looking at). The property is
     * `val` so the UI cannot accidentally
     * mutate the active session's state
     * without going through the ViewModel.
     */
    val activeSession: DesktopSessionState
        get() = sessions[activeIndex]

    companion object {
        /**
         * The initial state: one session
         * (the default `FREEFORM` desktop
         * with the standard 4 pinned apps).
         * The user creates additional
         * sessions via
         * [MultiDesktopShellViewModel.createSession].
         *
         * The `nextSessionNumber` starts at
         * 1 because the **default** session
         * (the initial one) is unnamed — the
         * user's "first" auto-named session
         * is "Session 1", the second is
         * "Session 2", etc. The default
         * session's name is just "Default"
         * (resolved from the dock label or
         * the index).
         */
        fun initial(): MultiDesktopShellState = MultiDesktopShellState(
            sessions = listOf(
                com.elysium.vanguard.features.desktop.DesktopShellViewModel.defaultInitialState(),
            ),
            activeIndex = 0,
            nextSessionNumber = 1,
        )
    }
}
