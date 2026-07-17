# Phase 28 — Main Screen ViewModel (§24 UX)

Date: 2026-07-15
Status: **Shipped** — `assembleDebug` green, 1266 tests, 0 failures, 2 skipped.

## What landed

The runtime's main-screen orchestration is now
JVM-testable end-to-end. The `MainScreenViewModel`
composes the four collaborators (WorkspaceManager +
DistroManager + WindowsVmManager + RuntimeEventBus)
and exposes a single `StateFlow<MainScreenState>` the
Compose UI consumes. No `AndroidViewModel`, no
`Context`, no `Application` — the class is pure JVM.

### Files

**Production (1 new):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/ui/MainScreenViewModel.kt`
  — the orchestrator. Composes the four collaborators,
  exposes `state: StateFlow<MainScreenState>`. The
  `MainScreenState` is a data class with five fields
  (`workspaces`, `linuxDistrosInstalled`,
  `linuxDistrosInstalling`, `windowsVmsRunning`,
  `recentEvents`). The `WorkspaceSummary` is the
  per-workspace render-ready shape (id, name, state,
  session counts by kind). The class is
  `AutoCloseable`; `close()` unsubscribes from the bus.

**Tests (1 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/ui/MainScreenViewModelTest.kt`
  — 8 unit tests covering: empty initial state,
  refresh from the four collaborators, picking up
  new workspaces, the recent-events buffer's cap,
  the close lifecycle, thread-safety under 4 × 50
  publishes + 4 × 20 refreshes, and the per-kind
  session counts in `WorkspaceSummary`.

**ADR (1 new):**

- `docs/adr/ADR-016-main-screen-viewmodel.md` —
  context, decision (single class composing four
  collaborators, single `StateFlow`, bus-driven
  updates), the `Context`-free rationale, the JVM-
  testable contract, consequences, alternatives,
  revisit triggers.

### Why this matters

Master order §24: "The runtime's main screen shows
the user's workspaces, the Linux distros they have
installed, the Windows VMs they have running, and a
'what just happened' feed of recent events." Until
Phase 28, every Compose ViewModel was
`AndroidViewModel`-flavoured (Phase 9.6.3's
`RuntimeViewModel`): it inherited from
`androidx.lifecycle.AndroidViewModel`, required an
`Application`, and was not JVM-testable. A bug in
the orchestration logic was caught by an on-device
integration test, not by a fast JVM test.

Phase 28 closes the gap. The `MainScreenViewModel`
is `Context`-free, JVM-testable end-to-end. The 8
unit tests cover the state machine, the buffer
cap, the close lifecycle, and the thread-safety
under concurrent bus emissions + refreshes. A
future `HiltViewModel` adapter wires the class into
the Compose UI; the JVM unit tests continue to
construct the class directly.

### What the ViewModel does

| Concern | Where it lives |
|---|---|
| The four collaborators | `WorkspaceManager` + `DistroManager` + `WindowsVmManager` + `RuntimeEventBus` |
| The single state object | `MainScreenState` data class with 5 fields |
| The per-workspace render shape | `WorkspaceSummary` (id, name, state, session counts by kind) |
| The bus subscription | One subscriber, appends to `recentEvents`, trims to `recentEventsCapacity` |
| The refresh path | `refresh()` rebuilds the state from the four collaborators; UI calls on `onResume` |
| The close lifecycle | `AutoCloseable.close()` unsubscribes from the bus |

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `MainScreenViewModelTest` | 8 | 0 |
| **Project total** | **1266** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Bug fix during this phase

The "WorkspaceSummary session counts" test originally
called `workspaceStore.save(ws)` directly, but the
`workspaceManager` was constructed before the save
and its in-memory `byId` map did not include the new
workspace. The test then saw an empty list. Fix: use a
fresh store + fresh manager that hydrates from the
saved workspace. The test now passes with the right
session counts.

## Next phase

The master order has a long tail (§25–§30
observability/security/observability, §22 Workspaces
[done in Phase 24], §15–§17 [done in Phases 17–18]).
The §36 ADR list is closed (001, 002, 005–016). The
next big build phase is **§24 UX continued** — the
actual Compose UI that consumes the ViewModel's
`StateFlow`. The ViewModel is the JVM-testable seam;
the UI is the production wiring.
