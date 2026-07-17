# Phase 31 — WindowsVmSessionRunner (§24 UX continued)

Date: 2026-07-17
Status: **Shipped** — `assembleDebug` green, 1307 tests, 0 failures, 2 skipped.

## What landed

The runtime's `SessionRunner` interface is now
complete for both session kinds. Phase 30 shipped
the `LinuxProotSessionRunner`; Phase 31 ships the
parallel `WindowsVmSessionRunner` that delegates to
the existing `WindowsVmManager` (Phase 22). The
workspace UI gets a uniform "Start session" / "Stop
session" surface for both Linux distros and Windows
VMs, backed by a JVM-testable state machine that
publishes typed events to the `RuntimeEventBus`.

### Files

**Production (2 new):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/runner/WindowsVmSessionBackend.kt`
  — the narrow seam the runner needs from the
  Windows VM layer. Three methods: `startVm`,
  `stopVm`, `getState`. Production wires the real
  `WindowsVmManager`; tests wire a hand-rolled fake.

- `app/src/main/java/com/elysium/vanguard/core/runtime/runner/WindowsVmSessionRunner.kt`
  — the impl. Mirrors the `LinuxProotSessionRunner`
  shape: per-session state in a
  `ConcurrentHashMap<SessionKey, SessionState>`;
  `start` / `stop` return `Result<SessionState>`;
  every successful transition publishes a
  `SessionStartedEvent` / `SessionStoppedEvent`;
  every failure publishes a
  `SessionStartFailedEvent`. The QEMU `pid` is
  the `SessionState.Running.pid`; the QMP `qmpPort`
  is the `SessionState.Running.port`. Rejects
  `LinuxProot` sessions with `UnsupportedKind` (a
  future `SessionRunnerRegistry` will dispatch by
  `session.kind`).

**Production (1 modified):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/windows/WindowsVmManager.kt`
  — now implements `WindowsVmSessionBackend` (3-line
  change: header `: WindowsVmSessionBackend` +
  `override` modifiers on the existing `startVm` /
  `stopVm` / `getState` methods). The public API
  is unchanged; existing call sites compile
  without edits.

**Tests (1 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/runner/WindowsVmSessionRunnerTest.kt`
  — 12 unit tests covering:
  - happy path: backend returns `Running(pid = 7777, qmpPort = 4444)` →
    `SessionState.Running(pid = 7777, port = 4444)` + a
    `SessionStartedEvent` on the bus with `launcherKind = "QEMU"`
  - the `Booting` case: backend returns `Booting` →
    `SessionState.Starting`; no `SessionStartedEvent` is published
    (a booting VM is not "started" from the runner's perspective)
  - the backend is called with the right `specId`
  - error path: backend returns `WindowsVmError.UnknownSpec` →
    state rolls to `Error` + `SessionStartFailedEvent` on the bus
  - wrong session kind (`LinuxProot`) → `UnsupportedKind`; the
    backend's `start` is not called
  - already running → `SessionAlreadyRunning`; the backend's
    `start` is called only once
  - stop path: `Running` → `Stopped` + `SessionStoppedEvent`
    on the bus with `exitCode = 0`
  - stop on `Idle` → `SessionNotRunning`
  - `state()` returns `Idle` for unknown sessions
  - `listActive` filters out non-live states
  - `activeCount` matches `listActive().size`
  - thread-safety under 4 × 20 concurrent starts on
    disjoint sessions (80 active sessions, zero lost
    writes)

**ADR (1 new):**

- `docs/adr/ADR-018-windows-vm-session-runner.md` —
  context, decision (the runner shape, the
  `WindowsVmSessionBackend` interface, the
  `WindowsVmState` → `SessionState` mapping
  table, the "no `LaunchedProcess` analog" rationale,
  the per-session state rationale, consequences,
  alternatives considered, revisit triggers.

### Bug fix during this phase

A typo in the test file was caught by the first
compile: `assertNull` was imported but never used,
producing a "Unused import" warning. Fix: drop the
unused import. (No new lint warnings — this is the
baseline.)

## Why this matters

Master order §24: the user sees a list of
workspaces, each with Linux sessions and Windows
sessions; tapping a session should start it. Until
Phase 30, the LinuxProot path had no orchestrator;
until Phase 31, the WindowsVm path had no
orchestrator either. The terminal path (Phase
9.6.3) had its own start logic, but it was tied to
the terminal session's lifecycle, not the
workspace's.

Phase 31 completes the JVM-testable action layer
for both session kinds. The `SessionRunner`
interface now has two impls, both JVM-tested, both
event-publishing, both thread-safe. The Compose UI
(a future phase) will call a `SessionRunnerRegistry`
that dispatches by `session.kind` — the
`LinuxProotSessionRunner` handles `LinuxProot`, the
`WindowsVmSessionRunner` handles `WindowsVm`. The
UI does not know which runner is which.

The 12 tests pin the start / stop / state /
listActive / error / bus / thread-safety contract
end-to-end with no QEMU process, no QMP socket, and
no real VM spec. The runner is JVM-testable
because:

- the only Windows-VM seam is the three-method
  `WindowsVmSessionBackend` interface,
- the only time seam is the injectable
  `clock: () -> Long`, and
- the bus is a `RecordingEventBus` whose `events`
  list is asserted on directly.

## What the runner does

| Concern | Where it lives |
|---|---|
| The collaborators | `WindowsVmSessionBackend` + `RuntimeEventBus` |
| The state machine | `SessionState` sealed (6 states) |
| The state mapping | `WindowsVmState` → `SessionState` (see ADR-018) |
| The action surface | `start` + `stop` (return `Result<SessionState>`) |
| The event publishing | 3 new event variants on every state transition (already added in Phase 30) |
| The thread-safety | `ConcurrentHashMap` per state |
| The clock | `clock: () -> Long` (default `System::currentTimeMillis`) |
| The error surface | 6 typed `SessionRunnerError` variants (shared with the LinuxProot runner) |

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `WindowsVmSessionRunnerTest` | 12 | 0 |
| **Project total** | **1307** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

The next phase is **SessionRunnerRegistry** — the
dispatch table that picks the right runner by
`session.kind`. The registry is a 30-line class
plus 4 tests. After that, the natural follow-ups
are:

- wire the runners into the `WorkspacesViewModel`
  (Phase 29 gains `startSession` / `stopSession`
  methods that go through the registry),
- wire the runners into the `MainScreenViewModel`
  (Phase 28 gains a `runningSessionCount` field
  that reads the sum of `listActive().size`),
- ship the Compose UI that consumes both
  ViewModels (the only piece of the runtime still
  inside the `features/` package, and the only
  piece that needs an Android-instrumented test
  for end-to-end coverage).
