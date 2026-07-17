# ADR-018 — WindowsVmSessionRunner

- Status: Accepted
- Date: 2026-07-17
- Phase: 31 (WindowsVmSessionRunner)
- Deciders: Mavis (Elysium Vanguard runtime)

## Context

Phase 30 shipped the [SessionRunner] (ADR-017) +
[LinuxProotSessionRunner] — the orchestrator that turns
a `Workspace` + `WorkspaceSession.LinuxProot` into a live
host OS process. The Phase 30 work closed the gap for
the LinuxProot path; the `WindowsVm` path was the
intentional omission ("a future phase 31+ ships a
`WindowsVmSessionRunner` that delegates to
`WindowsVmManager`").

The `WindowsVmManager` (Phase 22) already has a
working surface:

- `startVm(specId): Result<WindowsVmState>`
- `stopVm(specId): Result<WindowsVmState>`
- `getState(specId): WindowsVmState`

and a typed `WindowsVmState` (Stopped / Booting /
Running(pid, qmpPort) / Paused / Stopping / Error).
The manager's "process" is a QEMU VM, not a forked
shell; the `pid` is the QEMU process id, the `qmpPort`
is the QMP control socket.

What the manager does *not* have is the SessionRunner
shape: a single orchestrator the UI calls, a per-
session state map keyed by `(workspaceId, sessionId)`,
and a stream of typed runtime events (`SessionStartedEvent`
/ `SessionStoppedEvent` / `SessionStartFailedEvent`).
The Compose UI can call `WindowsVmManager.startVm` /
`stopVm` directly, but it loses:

- the per-session state machine (the manager tracks
  per-VM, not per-session — a workspace can hold
  several VM sessions, all sharing one VM spec),
- the bus event publishing (the manager's
  `VmStateChangedEvent` covers the *VM* state, not the
  *session* lifecycle),
- the typed `SessionRunnerError` surface (the manager
  uses its own `WindowsVmError` hierarchy).

## Decision

We ship a parallel runner — `WindowsVmSessionRunner`
— that mirrors the `LinuxProotSessionRunner` shape:

### 1. The `WindowsVmSessionBackend` interface

A narrow, three-method seam the runner needs from the
Windows VM layer:

```
fun startVm(specId: String): Result<WindowsVmState>
fun stopVm(specId: String): Result<WindowsVmState>
fun getState(specId: String): WindowsVmState
```

Production wires the real `WindowsVmManager` (which
implements the interface via a 3-line header change);
tests pass a hand-rolled fake. Per the engineering
rule "capture only the values the JVM-testable code
reads": the runner does not care about pause, resume,
attachUsb, or detachUsb. Those are follow-up
operations the runtime's VM screen invokes directly
on the manager.

### 2. The `WindowsVmSessionRunner` impl

The runner's control flow is the parallel of
`LinuxProotSessionRunner`:

1. `start()` rejects non-`WindowsVm` sessions with
   `UnsupportedKind`.
2. `start()` checks the current state (`isStartable`);
   if not, returns `SessionAlreadyRunning`.
3. `start()` calls `backend.startVm(specId)`. A
   `Result.failure` rolls the state to `Error` and
   publishes a `SessionStartFailedEvent`.
4. `start()` maps the backend's `WindowsVmState` to a
   `SessionState`:
   - `WindowsVmState.Stopped` → `SessionState.Stopped`
   - `WindowsVmState.Booting` → `SessionState.Starting`
   - `WindowsVmState.Running` → `SessionState.Running(pid, port = qmpPort)`
   - `WindowsVmState.Paused` → `SessionState.Running(pid = 0, port = null)` (a paused VM is a still-live VM)
   - `WindowsVmState.Stopping` → `SessionState.Stopping`
   - `WindowsVmState.Error` → `SessionState.Error`
5. `start()` publishes a `SessionStartedEvent` *only
   when the resulting state is `Running`* (a VM in
   `Booting` is not "started" from the runner's
   perspective; the runner's `state()` will reflect
   the boot completion on the next read).
6. `stop()` is the parallel: checks `isStoppable`,
   calls `backend.stopVm(specId)`, moves the state
   to `Stopped`, publishes a `SessionStoppedEvent`
   with `exitCode = 0` (QEMU does not surface an
   exit code to the runner; the manager's
   `Stopped` state is the canonical "done" signal).

### 3. Per-session state in a `ConcurrentHashMap`

Same shape as `LinuxProotSessionRunner`: state is
keyed by `SessionKey(workspaceId, sessionId)`,
stored in a `ConcurrentHashMap`, and read-then-
mutated atomically for the start / stop transitions.
The runner does not hold a `LaunchedProcess` analog
— the QEMU process is owned by the `WindowsVmManager`
+ the underlying QEMU backend.

## Why this shape

- **The runner is `Context`-free and JVM-testable.**
  The only OS / hardware seam is the
  `WindowsVmSessionBackend` interface, which the
  tests stub with a hand-rolled fake. 12 unit tests
  pin the start / stop / state / listActive / error
  / bus / thread-safety contract with no QEMU
  process and no real VM spec.

- **The runner does not duplicate the
  `WindowsVmManager`'s state.** The manager remains
  the source of truth for "is the VM running?". The
  runner's `SessionKey -> SessionState` map is a
  *projection* of the manager's per-spec state onto
  the per-session granularity the workspace UI needs.
  A follow-up phase may add a `refreshState` hook
  that asks the backend to re-read the VM's state
  on every `state()` call, but for Phase 31 the
  runner is "fire and forget" — once it has started
  a session, it tracks the lifecycle internally
  until the user calls `stop()`.

- **A small `WindowsVmSessionBackend` interface
  (three methods).** Per the engineering rule: the
  runner needs `startVm`, `stopVm`, and `getState`.
  It does not need `pause`, `resume`, `attachUsb`,
  `detachUsb`. Those are direct manager calls a
  future VM screen makes; they are not the
  runner's job.

- **Per-session `ActiveSession.launcherKind` is the
  literal string `"QEMU"`.** The runner does not
  use the `LauncherKind` enum (which is a Linux-side
  concept from Phase 9.6.3); it just tags the
  `ActiveSession` for the UI.

## Consequences

- The `WorkspacesViewModel` (Phase 29) gains
  `startSession` / `stopSession` methods that
  delegate to a `SessionRunnerRegistry` (a future
  phase) in a follow-up.
- The `MainScreenViewModel` (Phase 28) gains a
  `runningSessionCount` field that reads the sum of
  `listActive().size` across both runners in a
  follow-up phase.
- The Hilt module (Phase 21) gains a `@Provides
  @Singleton` for `WindowsVmSessionRunner` (and
  `LinuxProotSessionRunner`) in a follow-up phase.
- A `SessionRunnerRegistry` (a future phase)
  dispatches `(workspace, session) -> SessionRunner`
  by `session.kind` so the UI does not need to
  know which runner handles which kind.

## Alternatives considered

- **A single `SessionRunner` with a `when (kind)`
  switch.** Rejected: a future kind beyond
  `LinuxProot` and `WindowsVm` (Android-side
  microVM, WebAssembly, GPU passthrough) would
  drag in a third `if` branch. A per-kind impl
  composition is the same cost on the call-site
  side (`runner.start(ws, session)`) and keeps
  each impl focused.

- **Wire start / stop directly into
  `WindowsVmManager` and skip the runner.** Rejected:
  the manager is a VM orchestrator (Phase 22). Mixing
  in per-session state + bus event publishing +
  workspace-keyed state muddles the concerns; the
  manager would also need a `RuntimeEventBus`
  dependency, and the VM layer should not know
  about workspaces.

- **A coroutine-based `StateFlow<SessionState>` per
  VM session.** Rejected: a flow-per-session is the
  right shape for the UI, but the runner's contract
  is synchronous (`Result<SessionState>`). The UI
  adapter in a future phase projects the in-memory
  map onto a flow; the runner itself does not own
  the flow.

## Revisit triggers

- A session kind beyond `LinuxProot` and `WindowsVm`
  appears. Add a new `SessionRunner` impl and
  register it in the dispatch table.
- The runner needs to be observable from a
  coroutine (e.g. the UI binds a `StateFlow`
  directly). Add a
  `states: StateFlow<Map<SessionKey, SessionState>>`
  to the interface; the in-memory map already backs
  it.
- The runner needs to poll the backend on every
  `state()` read (so a QEMU VM that crashes without
  a `stop()` call still shows up as `Error`). Add a
  `refreshState` hook; today's design only reads
  the in-memory map.
- The runner needs to surface a real `exitCode`
  for QEMU (the Phase 31 impl hard-codes 0). Add a
  `queryExitCode(specId)` method to the backend.
