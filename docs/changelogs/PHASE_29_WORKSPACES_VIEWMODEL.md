# Phase 29 — Workspaces ViewModel (§24 UX continued)

Date: 2026-07-17
Status: **Shipped** — `assembleDebug` green, 1281 tests, 0 failures, 2 skipped.

## What landed

The runtime's workspace UI now has its action layer.
`MainScreenViewModel` (Phase 28) was the orchestrator
that *reads* the four collaborators; `WorkspacesViewModel`
is the action layer that *writes* to the workspace
manager and *publishes* the corresponding runtime event.
The two are intentionally split: a render-time
ViewModel and an action-time ViewModel, sharing the
same `StateFlow` shape contract.

### Files

**Production (1 new):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/ui/WorkspacesViewModel.kt`
  — the action layer. Composes `WorkspaceManager` +
  `RuntimeEventBus` + an injectable clock. Exposes
  `state: StateFlow<WorkspacesState>`. The
  `WorkspacesState` data class has two fields
  (`workspaces: List<Workspace>`, `lastActionResult:
  Result<Workspace>?`). The class is `AutoCloseable`;
  `close()` unsubscribes from the bus.

  The action surface is six methods, all returning
  `Result<Workspace>`:
  - `createWorkspace(name, sessions)` — creates a
    workspace, publishes a `WorkspaceStateChangedEvent`
    with `fromState = "(none)"`.
  - `pauseWorkspace(id)` / `activateWorkspace(id)` /
    `closeWorkspace(id)` — transition methods, all
    publish `WorkspaceStateChangedEvent` with the
    right `fromState` / `toState` pair.
  - `addSession(workspaceId, session)` / `removeSession(workspaceId, sessionId)` —
    session management methods, publish
    `SessionAddedEvent` / `SessionRemovedEvent`.

  The `transitionAndPublish` private helper keeps the
  three transition methods DRY without dragging in
  coroutine machinery — `inline fun` with a `() ->
  Result<Workspace>` block.

  On every successful action the ViewModel calls
  `refresh()` to re-read the workspace list. The bus
  subscriber also calls `refresh()` when an external
  `WorkspaceStateChangedEvent` / `SessionAddedEvent` /
  `SessionRemovedEvent` arrives (the bus is the
  contract that keeps the ViewModel in sync with any
  change, internal or external).

  Failed actions are recorded on
  `state.lastActionResult` so the UI can show a
  snackbar without polling the manager.

**Tests (1 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/ui/WorkspacesViewModelTest.kt`
  — 15 unit tests covering:
  - empty initial state hydrates from the manager
  - `createWorkspace` returns a `Workspace` + refreshes
  - `createWorkspace` publishes a
    `WorkspaceStateChangedEvent` on the bus with the
    right `atMs` / `fromState` / `toState`
  - `createWorkspace` with a blank name returns
    `WorkspaceError.InvalidName` and records the
    failure on `lastActionResult`
  - `pauseWorkspace` / `activateWorkspace` /
    `closeWorkspace` publish the right state pair
  - `addSession` publishes a `SessionAddedEvent` and
    refreshes; the new session is visible
  - `removeSession` publishes a `SessionRemovedEvent`
    and refreshes; the session is gone
  - `addSession` with a duplicate id records the
    failure and does NOT publish
  - external `WorkspaceStateChangedEvent` on the bus
    triggers a refresh (the new workspace appears)
  - external `SessionAddedEvent` on the bus triggers
    a refresh (the manager's view is re-read)
  - non-workspace events (e.g. `NetworkDecisionEvent`)
    do NOT trigger a refresh
  - `close()` unsubscribes from the bus
  - thread-safety under 4 × 20 concurrent
    `createWorkspace` calls — 80 distinct workspaces,
    zero lost writes

### Bug fix during this phase (collateral)

`WorkspaceState` was a `sealed class` with `object`
states (`Active`, `Paused`, `Closed`). The objects
had no `toString()` override, so `state.toString()`
returned the JVM FQN + hash
(`com.elysium.vanguard.core.runtime.workspaces.WorkspaceState$Active@f0da945`).
`WorkspacesViewModel.createWorkspace` uses
`ws.state.toString()` for the `toState` of the
event, so the first test run failed:

```
expected:<[Active]> but was:<[com.elysium.vanguard.core.runtime.workspaces.WorkspaceState$Active@f0da945]>
```

Fix: override `toString()` on each `WorkspaceState`
object to return its label ("Active", "Paused",
"Closed"). This is a one-line change per object in
`app/src/main/java/com/elysium/vanguard/core/runtime/workspaces/Workspace.kt`,
and every existing call site (the `WorkspaceManager`
tests, the `MainScreenViewModel` test, the production
manager) compares the objects by identity (`==`), not
by `toString`, so the change has zero behavioural
impact on the rest of the project.

The second compile failure was in the test file
itself: a backtick-quoted test name with `"e.g."`
(the dots) tripped Kotlin's
`Name contains illegal characters: .` check. Fix:
rename to `"non-workspace events like NetworkDecision
do not trigger a refresh"`. This is exactly the
Kotlin gotcha: backtick test names accept letters,
digits, spaces, hyphens, parentheses — but not
literal dots.

## Why this matters

Master order §24: the user taps "create workspace",
"pause", "activate", "close", "add session", or
"remove session" in the Compose UI. Each tap
* mutates the workspace manager,
* publishes a runtime event (so the audit log + the
  main-screen ViewModel pick the change up), and
* re-reads the state for the UI.

Phase 29 is the JVM-testable seam for all six
actions. The Compose-side `HiltViewModel` adapter
will wire the class into the UI; the JVM tests
continue to construct the class directly with
`RecordingEventBus` and an injectable clock.

The 15 tests cover the full action surface, the
typed error handling, the bus-driven refresh path,
the close lifecycle, and the thread-safety contract
under concurrent `createWorkspace` calls. Two
distinct bugs were caught by the suite (the
`toString` issue + the backtick name issue) — test
regressions are good news.

## What the ViewModel does

| Concern | Where it lives |
|---|---|
| The collaborators | `WorkspaceManager` + `RuntimeEventBus` |
| The single state object | `WorkspacesState` (workspaces, lastActionResult) |
| The action surface | 6 methods, all return `Result<Workspace>` |
| The event publishing | One event per successful action |
| The bus-driven refresh | Subscriber triggers `refresh()` on workspace + session events |
| The typed errors | `WorkspaceError.InvalidName`, `DuplicateSessionId`, etc. — surfaced via `lastActionResult` |
| The close lifecycle | `AutoCloseable.close()` unsubscribes from the bus |

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `WorkspacesViewModelTest` | 15 | 0 |
| **Project total** | **1281** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

The next build phase is **§24 UX continued** — the
actual Compose screens that consume both ViewModels
(`MainScreenViewModel` + `WorkspacesViewModel`). The
ViewModels are the JVM-testable seam; the UI is the
production wiring. The Compose layer is the only
piece of the runtime still inside the `features/`
package, and it is the only piece that needs an
Android instrumented test (or a Compose preview) for
end-to-end coverage.
