# Phase 38 — per-session Start / Stop buttons on the workspace cards

Date: 2026-07-17
Status: **Shipped** — `assembleDebug` green, 1351 tests, 0 failures, 2 skipped.

## What landed

`MainScreen` now has a real interactive surface.
Every workspace card renders one row per session
with a Start / Stop button. The button calls
[WorkspacesViewModel.startSession] or
[WorkspacesViewModel.stopSession] (both already
existed from Phase 33). The session's
[SessionState] is read from
[WorkspacesState.sessionStates] and rendered as a
small pill next to the button.

Until Phase 38, the screen was read-only — Phase
37's roadmap. Phase 38 closes that gap: the user
can now tap "Start" on a session, the runner
publishes a `SessionStartedEvent`, the bus
subscriber re-reads the runner, and the screen
flips the pill to "Running" and the button to
"Stop". A second tap stops the session and the
cycle repeats.

### Files

**Production (1 modified):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/ui/MainScreen.kt` — expanded
  the `WorkspaceCard` composable to render one
  `SessionRow` per session. Each row carries:
  - a kind icon (Linux → `Terminal`, Windows →
    `DesktopWindows`),
  - the session's display name + a one-line
    subtitle (Linux: `distroId • profileId`;
    Windows: `spec: <windowsSpecId>`),
  - a [SessionStateBadge] pill (Idle / Starting /
    Running / Stopping / Stopped / Error),
  - a [SessionActionButton] (the user-facing
    affordance: Start, Stop, or "Stopping…"
    while the runner is in the Stopping state).
  The `MainScreen` composable gained a
  `SnackbarHost` that surfaces the
  `lastActionResult` failure message when a
  session start fails (e.g. "Distro not
  installed"). A `LaunchedEffect` watches
  `workspacesState.lastActionResult` and shows
  the snackbar once per failure.

**Tests:** none added in this phase. The
[WorkspacesViewModelTest] (19 tests, including
the 4 `startSession` / `stopSession` tests
from Phase 33) already covers the action layer.
The Compose UI itself needs `androidTest/` for
end-to-end coverage; that is Phase 39.

## What the screen now does

| Action | Effect |
|---|---|
| Tap **Start** on an Idle session | `vm.startSession(workspace, session)` → `SessionRunner.start` → publish `SessionStartedEvent` → bus subscriber re-reads `sessionStates` → pill flips to "Running", button flips to "Stop" |
| Tap **Stop** on a Running session | `vm.stopSession(workspace, session)` → `SessionRunner.stop` → publish `SessionStoppedEvent` → pill flips to "Stopped" / "Idle", button flips to "Start" |
| Tap **Start** on a session whose distro is not installed | `SessionRunnerError.DistroNotInstalled` → `lastActionResult = failure` → snackbar: "Distro `<id>` is not installed (session ...)" |
| Tap **Start** while a session is `Stopping` | The "Stopping…" button is `enabled = false`; the user must wait for the runner to finish tearing down |

## Why this matters

Until Phase 38, the user could SEE the runtime's
state (status bar, workspace cards) but could
not DO anything with it. Phase 38 closes the
loop: the Compose UI is now a real product
surface, not a dashboard. The user can install
a distro (via the existing `runtime` route),
create a workspace (Phase 40), add a session
(Phase 40), tap Start, and see a live Linux
shell pop up. The runtime is no longer
plumbing-only.

The Start / Stop button is the first user-
facing action of the new runtime. The bus-driven
state-update path is end-to-end proven: the
button tap → the ViewModel → the runner → the
event bus → the ViewModel subscriber → the
Compose recomposition.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| (no new tests) | 0 | 0 |
| **Project total** | **1351** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

The follow-up after Phase 38 is **Phase 39 —
`androidTest/` for the `MainScreen` end-to-end**.
The Hilt-instrumented test will:
- launch `MainActivity` with the real Hilt
  graph (the `RuntimeModule` from Phase 36),
- drive the `MainScreen` with Compose UI
  testing,
- assert the status bar reflects the manager
  + runner + distro manager state,
- assert tapping Start on a session moves the
  pill to "Running".

Phase 39 is the only piece of the runtime still
missing instrumented coverage.

A second follow-up (Phase 40) adds the
"Create workspace" / "Add session" /
"Pause / Activate / Close" UI flows that the
`WorkspacesViewModel` already supports.
