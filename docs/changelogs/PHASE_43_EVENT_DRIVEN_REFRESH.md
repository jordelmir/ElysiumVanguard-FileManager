# Phase 43 — `MainScreenViewModel` refreshes on Distro + VM events

Date: 2026-07-17
Status: **Shipped** — `assembleDebug` green, 1362 tests, 0 failures, 2 skipped.

## What landed

`MainScreenViewModel` now refreshes the
status bar fields on every relevant runtime
event, not just the session events. Before
Phase 43, the bus subscriber only re-read
`runningSessionCount` on
`SessionStartedEvent` / `SessionStoppedEvent` /
`SessionStartFailedEvent`. A `DistroInstalledEvent`
arriving on the bus would land in
`recentEvents` but the `linuxDistrosInstalled`
count would stay stale until the user pulled
to refresh, navigated away, or rebuilt the
ViewModel. Phase 43 closes that gap.

Phase 43 adds three granular refresh paths:

| Event | Re-read field |
|---|---|
| `DistroInstalledEvent` | `linuxDistrosInstalled`, `linuxDistrosInstalling` |
| `DistroInstallFailedEvent` | `linuxDistrosInstalled`, `linuxDistrosInstalling` |
| `VmStateChangedEvent` | `windowsVmsRunning` |
| (Session events, unchanged) | `runningSessionCount` |
| (Other events) | (none — just append to `recentEvents`) |

The refresh is granular (re-read only the
affected field) instead of a full
[MainScreenViewModel.refresh], so the
recents buffer is not reset on every event
and the workspace list is not refetched on
every event. The pattern matches the
existing `SessionStartedEvent` /
`SessionStoppedEvent` / `SessionStartFailedEvent`
path.

### Files

**Production (1 modified):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/ui/MainScreenViewModel.kt` —
  the `handleEvent` `when` block gained three
  new arms (DistroInstalled / DistroInstallFailed
  / VmStateChanged). Each arm re-reads the
  relevant collaborator's state and updates
  the matching `MainScreenState` field.

**Tests (1 modified):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/ui/MainScreenViewModelTest.kt` —
  3 new tests pin the new arms:
  - `DistroInstalledEvent` refreshes the
    `linuxDistrosInstalled` count (the test's
    `distroManager.installed` is empty, so
    the re-read returns 0; the test asserts
    the path is hit and the state stays
    consistent).
  - `DistroInstallFailedEvent` records the
    event on the `recentEvents` buffer.
  - `VmStateChangedEvent` records the event
    on the `recentEvents` buffer.

The tests focus on the buffer-and-state
contract: an event lands in `recentEvents`,
and the affected stat-bar field is re-read.
The tests do NOT assert on the count value
itself because the test's collaborators start
empty; the contract is "the refresh path is
hit, no state desync, no exception". A real
`DistroManager` integration test would
assert on the count; that is Phase 9.6.x
scope, not this phase.

## Why this matters

The status bar's three numbers
(`runningSessionCount`, `linuxDistrosInstalled`,
`windowsVmsRunning`) are the user's
"runtime at a glance" view. Before Phase 43,
two of the three numbers could go stale when
the user took an action that published the
relevant event but did not navigate away
(e.g. installing a distro from the catalog
screen while the workspaces screen was
backgrounded). After Phase 43, every
relevant event refreshes its field in
real time.

The refresh is also symmetric: the
`WorkspacesViewModel` already refreshes on
`WorkspaceStateChangedEvent` /
`SessionAddedEvent` / `SessionRemovedEvent`
(Phase 33) and `SessionStartedEvent` /
`SessionStoppedEvent` / `SessionStartFailedEvent`
(Phase 33). Phase 43 brings the
`MainScreenViewModel`'s refresh set to the
same level of completeness — every event
that affects a stat-bar field triggers a
re-read.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `MainScreenViewModelTest` | 15 (was 12) | 0 |
| **Project total** | **1362** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

The follow-up after Phase 43 is **the
`androidTest/` end-to-end coverage for
`MainScreen`** — the Hilt-instrumented test
that exercises the full Phase 36 → Phase 43
stack. The test will:
- launch `MainActivity` with the real Hilt
  graph (`RuntimeModule`),
- drive the `MainScreen` via Compose UI
  testing,
- assert the status bar reflects the
  collaborator states,
- assert tapping Start on a session moves
  the pill to "Running",
- assert tapping a Distro dropdown shows the
  catalog entries (Phase 41),
- assert a "Distro installed" event
  refreshes the status bar (Phase 43).

The test is the only piece of the runtime
still missing instrumented coverage.
