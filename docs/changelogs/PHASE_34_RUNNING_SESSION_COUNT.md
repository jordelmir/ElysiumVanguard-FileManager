# Phase 34 — `runningSessionCount` in `MainScreenViewModel` (§24 UX continued)

Date: 2026-07-17
Status: **Shipped** — `assembleDebug` green, 1325 tests, 0 failures, 2 skipped.

## What landed

`MainScreenViewModel` (Phase 28) now exposes a
**live count of running sessions** in the status bar.
The `MainScreenState` gained a
`runningSessionCount: Int` field. The value is
`SessionRunner.activeCount()` (Phase 32 registry) —
the total of every `Starting` / `Running` /
`Stopping` session across both kinds (Linux proot
+ Windows VM).

The value is refreshed on three triggers:

1. **`init`** — the ViewModel hydrates the field
   on construction.
2. **`refresh()`** — the manual and
   `forceRefresh()` paths re-read the runner.
3. **Bus events of kind `SessionStartedEvent` /
   `SessionStoppedEvent` /
   `SessionStartFailedEvent`** — the bus
   subscriber re-reads the count without waiting
   for a manual refresh, so the status bar updates
   within the same tick as the user tapping Start
   or Stop.

Until Phase 34, the status bar had to do its own
"how many sessions are live?" computation by
calling `registry.listActive().size` from the
Compose side. The value is now part of the
ViewModel's state contract; the UI just reads
`state.runningSessionCount`.

### Files

**Production (1 modified):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/ui/MainScreenViewModel.kt`
  — added `sessionRunner: SessionRunner` as a
  fifth constructor parameter. The `refresh()`
  function reads `sessionRunner.activeCount()` and
  writes it into `MainScreenState.runningSessionCount`.
  The bus subscriber, on a session-lifecycle event,
  re-reads the count without doing a full
  `workspaces` / `distros` / `windowsVms` refresh
  (cheaper path). The `MainScreenState` data class
  gained a `runningSessionCount: Int = 0` field.

**Tests (1 modified):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/ui/MainScreenViewModelTest.kt`
  — added a `FakeSessionRunner` test fixture
  (the test sets `activeCount` directly to control
  what `activeCount()` returns; the rest of the
  interface is a stub). The 8 existing
  `MainScreenViewModel` constructions were updated
  to pass `sessionRunner = sessionRunner` (a
  1-line change each). Four new tests pin the
  field:
  - `runningSessionCount hydrates from the runner
    on construction` — the count is read in `init`.
  - `runningSessionCount updates when the runner
    count changes` — `refresh()` re-reads.
  - `SessionStartedEvent` triggers a refresh of
    `runningSessionCount` without a manual
    `refresh()` call.
  - `SessionStoppedEvent` triggers a refresh of
    `runningSessionCount`.

## Why this matters

Master order §24: the main screen's status bar
shows "N sessions running". Until Phase 34, the
ViewModel exposed `workspaces` /
`linuxDistrosInstalled` / `windowsVmsRunning` /
`recentEvents` but not a session count. The
Compose side had to do the runner lookup itself
— duplicating the dependency across the UI
boundary.

Phase 34 closes that gap. The Compose UI now
reads a single field from `MainScreenState`. The
ViewModel is the single source of truth for
"what is the runtime doing right now", and the
count is consistent with every other field in
the state (refreshed in the same paths).

The 4 new tests pin the four scenarios:
construction hydration, manual refresh, and
the two lifecycle events that re-read the count.
The 8 existing tests were updated in 1 line
each (adding `sessionRunner = sessionRunner` to
the constructor).

## What the ViewModel does

| Concern | Where it lives |
|---|---|
| The collaborators | `WorkspaceManager` + `DistroManager` + `WindowsVmManager` + `SessionRunner` + `RuntimeEventBus` |
| The single state object | `MainScreenState` (workspaces, linux counts, windows count, runningSessionCount, recentEvents) |
| The refresh triggers | `init` + `refresh()` + 3 session-lifecycle bus events |
| The session-count refresh path | `sessionRunner.activeCount()` (cheap — registry sums the two runners) |
| The close lifecycle | `AutoCloseable.close()` unsubscribes from the bus |

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `MainScreenViewModelTest` | 12 (was 8) | 0 |
| **Project total** | **1325** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

The follow-up after Phase 34 is **the Compose
UI that consumes both ViewModels** —
`MainScreenViewModel` and `WorkspacesViewModel`
— to render the main screen (status bar,
workspace list, per-session Start / Stop
buttons). This is the only piece of the runtime
still inside the `features/` package, and the
only piece that needs an Android-instrumented
test for end-to-end coverage (Compose rendering,
Hilt ViewModel binding, lifecycle wiring).
