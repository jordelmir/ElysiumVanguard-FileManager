# Phase 33 — Runners in WorkspacesViewModel (§24 UX continued)

Date: 2026-07-17
Status: **Shipped** — `assembleDebug` green, 1321 tests, 0 failures, 2 skipped.

## What landed

The `WorkspacesViewModel` (Phase 29) now owns the
session start / stop actions. The ViewModel takes
a `SessionRunner` (the Phase 32 registry) as a
new constructor parameter; the new
`startSession(workspace, session)` and
`stopSession(workspace, session)` methods
delegate to the runner. The `WorkspacesState`
gained a `sessionStates: Map<SessionKey,
SessionState>` field the Compose UI uses to
render the per-session start / stop / running
badge.

Until Phase 33, the user could see a workspace
and add / remove sessions in it, but the
"Start" / "Stop" buttons had no action — the
ViewModel had no runner reference. Phase 33
closes the gap. The Compose UI gains a single
`vm.startSession(workspace, session)` call
that flows through the registry → the right
runner impl → the host OS process (or QEMU VM).

### Files

**Production (1 modified):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/ui/WorkspacesViewModel.kt`
  — added `sessionRunner: SessionRunner` as a
  constructor parameter. Two new action methods
  delegate to the runner:
  - `startSession(workspace, session): Result<SessionState>`
  - `stopSession(workspace, session): Result<SessionState>`
  The `WorkspacesState` data class gained a
  `sessionStates: Map<SessionKey, SessionState>`
  field. The `refreshSessionStates()` method
  re-reads the runner's `listActive()` and
  projects it onto the per-`(workspaceId,
  sessionId)` map. The bus subscriber also
  re-reads the runner's state on every
  `SessionStartedEvent` / `SessionStoppedEvent` /
  `SessionStartFailedEvent` so the UI sees
  external session-lifecycle events.

**Tests (1 modified):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/ui/WorkspacesViewModelTest.kt`
  — added a `FakeSessionRunner` test fixture +
  four new tests:
  - `startSession` delegates to the runner and
    refreshes `sessionStates` on success
  - `startSession` records a failure on
    `lastActionResult` when the runner fails
  - `stopSession` delegates to the runner
  - an external `SessionStartedEvent` from the
    bus triggers `refreshSessionStates` (the
    runner-published event flows back through
    the bus; the ViewModel re-reads state)
  The existing 15 tests were updated to pass
  the `FakeSessionRunner` to the new constructor.

### Bug fix during this phase (collateral)

The Phase 32 thread-safety test failed once
with "expected:<80> but was:<79>" — a
concurrent write to the `RecordingRunner`'s
`startCalls: mutableListOf` lost an entry
because `mutableListOf` is not thread-safe.
Fix: the fake's `startCalls`, `stopCalls`, and
`activeSessions` are now
`Collections.synchronizedList(mutableListOf())`,
and the test asserts on an `AtomicInteger`
counter (`startCount`) instead of the list
size. This is a test-discipline gotcha: any
field a concurrent test asserts on must be
either a thread-safe collection, a copy-on-
write snapshot, or a counter.

## Why this matters

Master order §24: the user sees a list of
workspaces, each with Linux sessions and
Windows sessions; tapping a session's "Start"
button starts it. Until Phase 33, the
ViewModel had no `startSession` method — the
UI's button click had no effect. Phase 33
closes the gap. The Compose UI (a future
phase) will call `vm.startSession(workspace,
session)` and observe the resulting
`SessionStartedEvent` on the bus via the
`WorkspacesState.sessionStates` map.

The 4 new tests pin the start / stop
delegation, the failure path, and the
external-event-driven refresh. The existing
15 tests were updated in 2 lines (the
constructor calls) — no behavioural change.

## What the ViewModel does

| Concern | Where it lives |
|---|---|
| The collaborators | `WorkspaceManager` + `RuntimeEventBus` + `SessionRunner` |
| The single state object | `WorkspacesState` (workspaces, sessionStates, lastActionResult) |
| The action surface | 8 methods (6 workspace-level + 2 session-level) |
| The bus subscription | 6 event types (3 workspace + 3 session) |
| The session-state map | `Map<SessionKey, SessionState>` keyed by `(workspaceId, sessionId)` |
| The close lifecycle | `AutoCloseable.close()` unsubscribes from the bus |

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `WorkspacesViewModelTest` | 19 (was 15) | 0 |
| **Project total** | **1321** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

The next phase is **`runningSessionCount` in
`MainScreenViewModel`** (Phase 28). The main
screen's status bar shows "N sessions
running" — the value is `registry.listActive()
.size`. The follow-up after that is the
Compose UI that consumes both ViewModels
(the only piece of the runtime still inside
the `features/` package, and the only piece
that needs an Android-instrumented test for
end-to-end coverage).
