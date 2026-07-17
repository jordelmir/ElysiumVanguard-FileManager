# Phase 46 — Bulk "Start all" / "Stop all" actions per workspace

Date: 2026-07-17
Status: **Shipped** — `assembleDebug` green, 1367 tests, 0 failures, 2 skipped.

## What landed

The workspace's 3-dot menu now exposes
"Start all" + "Stop all" bulk actions. Until
Phase 46, the user had to tap Start on every
session in a workspace individually — a
workspace with 5 sessions required 5 taps to
power it on. Phase 46 adds a single menu item
that delegates to
[WorkspacesViewModel.startAllSessions] /
[WorkspacesViewModel.stopAllSessions], which
iterate the workspace's sessions and call the
per-session `start` / `stop` only for sessions
whose current state is startable / stoppable.

The bulk action is **state-aware**: a session
that is already Running is skipped by
`startAllSessions`; a session that is Idle is
skipped by `stopAllSessions`. The two
operations are mutually safe — the user can
tap them in any order without producing
inconsistent state.

### Files

**Production (2 modified):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/ui/WorkspacesViewModel.kt` —
  added two new methods:
  - `startAllSessions(workspaceId: String): Int`
    — iterates the workspace's sessions, calls
    `sessionRunner.state(...)` per session, and
    calls `startSession(...)` only for
    startable states (Idle / Stopped / Error).
    Returns the number of sessions actually
    started.
  - `stopAllSessions(workspaceId: String): Int`
    — mirror of above, for stoppable states
    (Starting / Running).

- `app/src/main/java/com/elysium/vanguard/core/runtime/ui/MainScreen.kt` —
  the `WorkspaceCard`'s 3-dot menu gained two
  new `DropdownMenuItem`s at the top:
  - **Start all** (`PlayArrow` icon)
  - **Stop all** (`Stop` icon)
  The items render regardless of the
  workspace's state — the per-session
  startability check happens in the
  ViewModel. The `WorkspaceCard` composable
  gained two new `onStartAll` / `onStopAll`
  parameters.

**Tests (1 modified):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/ui/WorkspacesViewModelTest.kt` —
  5 new tests pin the bulk-action contract:
  - `startAllSessions` starts every startable
    session in the workspace (3 sessions → 3
    start calls, return value 3).
  - `startAllSessions` skips sessions that are
    already Running (2 sessions, 1 pre-marked
    Running → 1 start call, return value 1).
  - `startAllSessions` on a non-existent
    workspace returns 0 (no throw, no
    start calls).
  - `stopAllSessions` stops every stoppable
    session in the workspace (2 sessions, 2
    pre-marked Running → 2 stop calls,
    return value 2).
  - `stopAllSessions` skips sessions that are
    not stoppable (2 sessions, 1 Running, 1
    Idle → 1 stop call, return value 1).

The pre-marking pattern (adding to
`runner.activeSessions` directly) lets the
test simulate the runner's state without a
real start. The `FakeSessionRunner.state()`
reads from `activeSessions`, so the bulk
methods see the same view of the world the
real runner would.

## What the screen now does

The 3-dot menu on every workspace card
exposes (in order):

1. **Start all** — start every startable
   session in the workspace.
2. **Stop all** — stop every stoppable
   session in the workspace.
3. **Pause** — pause the workspace (state:
   Active).
4. **Activate** — activate the workspace
   (state: Paused / Closed).
5. **Close** — close the workspace (state:
   not Closed).

A user with a 5-session workspace taps
"Start all" once → all 5 sessions start. The
menu's bulk actions are the power-user's
shortcut; the per-session Start / Stop
buttons are still the per-row affordance.

## Why this matters

The runtime path is now feature-complete
for the workspace management surface:

- **Per-session**: Start / Stop (Phase 38) +
  Open (Phase 45) + Remove (Phase 40)
- **Per-workspace**: Pause / Activate /
  Close (Phase 40) + Start all / Stop all
  (Phase 46) + Add Session (Phase 40) +
  Create (Phase 40)

A user can manage every aspect of the
runtime from a single `MainScreen`
instance. The follow-up phases (VNC viewer,
SSH client, snapshot layers, app launcher)
are *additions* to the runtime, not
prerequisites for managing it.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `WorkspacesViewModelTest` | 24 (was 19) | 0 |
| **Project total** | **1367** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

The follow-up after Phase 46 is the original
Phase 9.6 roadmap from the Worldwide Vision
doc:

- **Phase 9.6.5**: VNC client in Compose —
  the natural complement to Phase 45's
  "Open" affordance for WindowsVm sessions.
  Tapping Open on a Running Windows session
  would launch the VNC viewer instead of the
  snackbar.
- **Phase 9.6.6**: SSH client + X11-forwarding
  tunnel — a remote console.
- **Phase 9.6.7**: Snapshot layers — checkpoint
  / rollback.
- **Phase 9.6.8**: Bash auto-completion +
  tmate / tmux.
- **Phase 9.6.9**: App launcher — detect GUI
  apps the user installed, spawn them in a
  Compose window.

Each is multi-week. The new runtime's
management surface is now complete; the
runtime-as-product work starts with the VNC
viewer.
