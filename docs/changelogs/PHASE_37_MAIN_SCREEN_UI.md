# Phase 37 — `MainScreen` Compose UI (read-only: status bar + workspace list)

Date: 2026-07-17
Status: **Shipped** — `assembleDebug` green, 1351 tests, 0 failures, 2 skipped.

## What landed

The new runtime has a home screen. [MainScreen] is
the Compose entry point that consumes both new
`@HiltViewModel`s:

- [MainScreenViewModel] — the status bar's
  source of truth (`runningSessionCount`,
  `linuxDistrosInstalled`, `windowsVmsRunning`).
- [WorkspacesViewModel] — the workspace list's
  source of truth ([Workspace] objects from the
  manager).

The screen is read-only in Phase 37: a TopAppBar,
three stat cards, a workspace list. No Start /
Stop / Create buttons yet — those are Phase 38.
The "no workspaces yet" empty state links to the
existing `runtime` route (browse distros) and
`terminal` route (open a shell) so a first-run
user has a productive path.

The route is `runtime_main` in the NavHost. A
follow-up phase will add a button on the
dashboard to navigate to it; for now it is
reached from the existing `runtime` flow.

### Files

**Production (2 new, 1 modified):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/ui/MainScreen.kt` — new
  Compose composable. ~330 lines.
  - `MainScreen` (top-level): the Scaffold with
    TopAppBar + status row + workspace list or
    empty state.
  - `StatusCard`: a single stat card (icon,
    label, value).
  - `WorkspaceCard`: a single workspace card
    (name, state chip, session summary line).
  - `StateChip`: a small pill that renders the
    workspace's state (Active / Paused / Closed)
    in a theme-aware color.
  - `EmptyWorkspacesState`: the empty state with
    two outline buttons linking to the existing
    runtime / terminal routes.
  - `sessionSummary`: a small helper that
    formats `"3 sessions • 2 Linux • 1 Windows"`.
- `app/src/main/java/com/elysium/vanguard/MainActivity.kt` — added a new
  `composable("runtime_main")` block in the
  NavHost. The composable opens the new screen
  with `onBack` (pops the stack), `onOpenRuntime`
  (navigates to `runtime`), and `onOpenTerminal`
  (navigates to `terminal`).

**Tests:** none added in this phase. The two
ViewModels already have full JVM coverage
([MainScreenViewModelTest], 12 tests;
[WorkspacesViewModelTest], 19 tests). The
Compose UI itself needs `androidTest/` for
end-to-end coverage; that is Phase 39 (Hilt
instrumented, the only piece of the runtime
still missing).

## What the screen does

| Concern | Where it lives |
|---|---|
| Status bar values | `MainScreenViewModel.state.collectAsState()` |
| Workspace list values | `WorkspacesViewModel.state.collectAsState()` |
| TopAppBar | `Scaffold(topBar = ...)` |
| Stat cards | `StatusCard` composable × 3 in a `Row` |
| Workspace cards | `LazyColumn(items(workspacesState.workspaces, key = { it.id }))` |
| Empty state | `EmptyWorkspacesState` composable |
| State chip | `StateChip` composable (Material 3 `secondary` for Paused, `error` for Closed) |
| Back nav | `onBack` → `navController.popBackStack()` |
| Empty-state links | `onOpenRuntime` / `onOpenTerminal` (outline buttons) |

## Why this matters

Until Phase 37, every new piece of the runtime
(ViewModels, managers, runners, Hilt module)
was JVM-testable but invisible to the user.
Phase 37 is the first Compose UI that consumes
the new ViewModels end-to-end. The user now sees
a real "Sovereign Runtime" home screen with
live data: the running session count, the number
of distros installed, the number of Windows VMs
running, and a list of every workspace the
manager has on disk.

The screen is the new app's primary entry point
for the runtime path. The follow-up phases (38
+ 39) add the per-session Start / Stop actions
and the `androidTest/` that pins the screen
end-to-end.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| (no new tests) | 0 | 0 |
| **Project total** | **1351** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

The follow-up after Phase 37 is **Phase 38 —
per-session Start / Stop actions on the workspace
cards**. The `WorkspacesViewModel` already has
`startSession(workspace, session)` and
`stopSession(workspace, session)` methods
(Phase 33); Phase 38 wires them to buttons on
the `WorkspaceCard` and updates the
`SessionState` badge per session. A second
follow-up (Phase 39) adds the `androidTest/`
end-to-end coverage.
