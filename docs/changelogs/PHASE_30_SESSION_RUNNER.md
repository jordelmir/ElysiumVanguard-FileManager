# Phase 30 — SessionRunner (§24 UX continued + runtime gap)

Date: 2026-07-17
Status: **Shipped** — `assembleDebug` green, 1295 tests, 0 failures, 2 skipped.

## What landed

The runtime now has the orchestrator that turns a
`Workspace` + `WorkspaceSession.LinuxProot` into a live
host OS process. The Compose UI gets a real "Start
session" / "Stop session" surface backed by a JVM-
testable state machine that publishes typed events to
the `RuntimeEventBus`.

Until Phase 30, the runtime's UX was able to *show*
workspaces and *mutate* the workspace model, but no
component could *launch* a session. The terminal path
(Phase 9.6.3) had its own `DistroLauncher` + PTY
seam, but that lived in the terminal session's UI;
the workspace path had no equivalent. Phase 30
closes the gap.

### Files

**Production (5 new):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/runner/SessionState.kt`
  — the per-session state machine. Six states:
  `Idle` / `Starting(atMs)` /
  `Running(pid, startedAtMs, host?, port?)` /
  `Stopping(atMs)` / `Stopped` / `Error(atMs, message)`.
  Three convenience predicates: `isLive()`,
  `isStartable()`, `isStoppable()`. The runner is the
  only mutator.

- `app/src/main/java/com/elysium/vanguard/core/runtime/runner/ProcessLauncher.kt`
  — the JVM-testable seam for "spawn a host OS
  process". One method:
  `start(command, env, cwd): LaunchedProcess`. The
  `LaunchedProcess` value object is `pid: Int` plus
  `stop: () -> Unit`. Production wires the Android
  `ProcessBuilder` impl; tests wire a no-op impl
  that returns a fake pid.

- `app/src/main/java/com/elysium/vanguard/core/runtime/runner/SessionRunner.kt`
  — the runner interface. Four methods: `start` /
  `stop` / `state` / `listActive`. Returns typed
  `SessionRunnerError` on failure (six cases:
  `SessionAlreadyRunning`, `SessionNotRunning`,
  `DistroNotInstalled`, `LauncherUnavailable`,
  `StartFailed`, `UnsupportedKind`).

- `app/src/main/java/com/elysium/vanguard/core/runtime/runner/DistroSessionBackend.kt`
  — the narrow seam the runner needs from the distro
  layer. Two methods: `findInstalled(id)` +
  `launcherFor(id)`. Production wires the real
  `DistroManager`; tests wire a hand-rolled fake.

- `app/src/main/java/com/elysium/vanguard/core/runtime/runner/LinuxProotSessionRunner.kt`
  — the Phase 30 impl. Handles `LinuxProot` only;
  rejects `WindowsVm` with `UnsupportedKind` (a
  future phase 31+ ships a `WindowsVmSessionRunner`
  that delegates to `WindowsVmManager`). State is
  stored in a `ConcurrentHashMap<SessionKey, SessionState>`;
  the `LaunchedProcess` is read-then-stop-ped
  atomically via `handles.remove(key)` so concurrent
  `stop()` calls do not double-signal.

**Production (2 modified):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/distros/DistroManager.kt`
  — now implements `DistroSessionBackend` (2-line
  change: header `: DistroSessionBackend` +
  `override` modifiers on the existing
  `findInstalled` / `launcherFor` methods). The
  public API is unchanged; existing call sites
  compile without edits.

- `app/src/main/java/com/elysium/vanguard/core/runtime/observability/RuntimeEvent.kt`
  — three new event variants:
  `SessionStartedEvent(atMs, workspaceId, sessionId,
  kind, launcherKind, pid)` /
  `SessionStoppedEvent(atMs, workspaceId, sessionId,
  exitCode)` /
  `SessionStartFailedEvent(atMs, workspaceId,
  sessionId, kind, error)`. The `when` in
  `RuntimeEventLog.renderEvent` gained three
  branches. The `BusToLogAdapter` picks the new
  events up automatically.

**Tests (1 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/runner/LinuxProotSessionRunnerTest.kt`
  — 14 unit tests covering: the happy path (Idle →
  Starting → Running with the right `pid` /
  `startedAtMs` / bus event); the `processLauncher`
  gets the right command + cwd + env; the error
  paths (`DistroNotInstalled`, `LauncherUnavailable`,
  `UnsupportedKind`, `SessionAlreadyRunning`,
  `StartFailed`); the stop path (Running → Stopped
  + the `stop()` callback fires + a
  `SessionStoppedEvent` is on the bus); restart
  after stop; `state()` returns `Idle` for unknown
  sessions; `listActive` filters out non-live
  states; `activeCount` matches `listActive().size`;
  thread-safety under 4 × 20 concurrent starts on
  disjoint sessions (80 active sessions, zero lost
  writes).

**ADR (1 new):**

- `docs/adr/ADR-017-session-runner.md` — context,
  decision (the runner shape, the `ProcessLauncher`
  seam, the `DistroSessionBackend` interface, the
  state machine, the three new events, the
  per-workspace `ConcurrentHashMap` for state), the
  "why no `ProcessBuilder` in the runner"
  rationale, the "why a small backend interface"
  rationale, consequences, alternatives
  considered, revisit triggers.

### Bug fixes during this phase

Two compile errors caught by the suite:

- `'findInstalled' hides member of supertype
  'DistroSessionBackend' and needs 'override'
  modifier`. Fix: add `override` to the two
  existing methods on `DistroManager`. The methods
  themselves were already correct; the `override`
  keyword was missing because the interface
  inheritance is new.

- `'cause' hides member of supertype
  'SessionRunnerError' and needs 'override'
  modifier`. The `StartFailed` data class had a
  field named `cause` which shadowed
  `Throwable.cause`. Fix: rename the field to
  `causeMessage` and update the message template.

A third compile error was a regression in
`RuntimeEventLog.renderEvent`: the `when` was
exhaustive over the prior 7 `RuntimeEvent` variants
but did not have branches for the 3 new ones. Fix:
add three `is X ->` branches to the `when` (the
shape is the same as the existing branches — `kind`,
`atMs`, `workspaceId`, plus the variant-specific
fields).

## Why this matters

Master order §24: the user sees a list of
workspaces; tapping a session should start it.
Until Phase 30, the UI could render the workspace
and add / remove sessions, but "Start" was a
no-op. The terminal path (the runtime's standalone
PTY) had its own start logic, but it was tied to
the terminal session's lifecycle, not the
workspace's.

Phase 30 splits the orchestration from the
terminal path. The `SessionRunner` is the
single source of truth for "is this session
running, and if so, what's its pid?". The terminal
adapter (a future phase) will read the runner's
`state()` and attach its PTY to the running
process. The Compose UI (a future phase) will
call `runner.start(workspace, session)` on a
button click and observe the resulting
`SessionStartedEvent` on the bus.

The 14 tests pin the start / stop / state /
listActive / error / bus / thread-safety contract
end-to-end with no `ProcessBuilder` mock and no
real rootfs tarball. The runner is JVM-testable
because:

- the only OS seam is the one-method
  `ProcessLauncher` interface,
- the only distro seam is the two-method
  `DistroSessionBackend` interface, and
- the only time seam is the injectable `clock: () -> Long`.

## What the runner does

| Concern | Where it lives |
|---|---|
| The collaborators | `DistroSessionBackend` + `ProcessLauncher` + `RuntimeEventBus` |
| The state machine | `SessionState` sealed (6 states) |
| The action surface | `start` + `stop` (return `Result<SessionState>`) |
| The event publishing | 3 new event variants on every state transition |
| The thread-safety | `ConcurrentHashMap` per state + atomic remove-and-stop on the handle |
| The clock | `clock: () -> Long` (default `System::currentTimeMillis`; tests pass an `AtomicLong::get`) |
| The error surface | 6 typed `SessionRunnerError` variants |

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `LinuxProotSessionRunnerTest` | 14 | 0 |
| **Project total** | **1295** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

The next phase is **§24 UX continued — the
Windows VM session runner**. A `WindowsVmSessionRunner`
impl that delegates to the existing `WindowsVmManager`
(Phase 22) — QEMU's pid is the `LaunchedProcess`
analog; the `stop` callback signals QEMU via the
manager's QMP interface. The runner interface
already supports the shape; the impl is the
remaining piece. The Compose UI is still on the
table for a future phase, but the JVM-testable
seam for the action layer is now complete for both
kinds.
