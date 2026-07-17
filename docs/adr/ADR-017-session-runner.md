# ADR-017 — SessionRunner Architecture

- Status: Accepted
- Date: 2026-07-17
- Phase: 30 (SessionRunner)
- Deciders: Mavis (Elysium Vanguard runtime)

## Context

The runtime has:

- A `DistroManager` (Phase 9.6.2) that knows which distros are
  installed and which rootfs directory each one lives in,
- A `DistroLauncher` interface (Phase 9.6.3) that builds a host-
  side shell command for a given rootfs (the "what would the
  command line look like" abstraction, used by the terminal
  session's UI),
- A `Workspace` + `WorkspaceSession` model (Phase 24) where each
  session is either `LinuxProot(distroId, profileId)` or
  `WindowsVm(specId)`,
- A `RuntimeEventBus` (Phase 25) that fans state transitions
  out to the audit log + the main-screen ViewModel.

What the runtime does **not** have is the orchestrator that
turns a `(workspace, session)` pair into a live host OS
process (or QEMU VM). The Compose UI currently has no way to
"start a session" — it can show the workspace, add and remove
sessions, but tapping a session has no effect.

Phase 9.6.3 wired the `DistroLauncher` for the terminal session's
PTY, but the PTY path is its own thing (it owns the
ProcessBuilder and the termios). The session path needs an
orthogonal orchestrator that:

- keeps the per-session state machine in memory,
- fans state transitions onto the `RuntimeEventBus`,
- is JVM-testable end-to-end (no `Context`, no `ProcessBuilder`
  reaching into the test classpath).

## Decision

We ship a new sub-system — `core.runtime.runner` — with the
following surface:

### 1. The `SessionState` sealed class

```
Idle ──start()──▶ Starting ──ok──▶ Running ──stop()──▶ Stopping ──exit──▶ Stopped
                     │                                          │
                     └──────────────┐                           │
                                    ▼                           ▼
                                  Error                        Error
```

Six states, `isLive()` / `isStartable()` / `isStoppable()`
helpers. The `when` over this hierarchy is the runner's
control flow.

### 2. The `SessionRunner` interface

```
fun start(workspace, session): Result<SessionState>
fun stop(workspace, session): Result<SessionState>
fun state(workspaceId, sessionId): SessionState
fun listActive(): List<ActiveSession>
```

Returns typed `SessionRunnerError` on failure (six cases:
`SessionAlreadyRunning`, `SessionNotRunning`,
`DistroNotInstalled`, `LauncherUnavailable`, `StartFailed`,
`UnsupportedKind`).

### 3. The `ProcessLauncher` seam

The runner's only direct dependency on the host OS is
abstracted behind a one-method interface:

```
fun start(command, env, cwd): LaunchedProcess
```

`LaunchedProcess` is a value object: `pid: Int` +
`stop: () -> Unit`. The production Android impl uses
`ProcessBuilder.start()`; the test impl records the call and
returns a fake pid. The runner treats the launched process
as a black box.

### 4. The `LinuxProotSessionRunner` impl (Phase 30)

The Phase 30 impl handles only `WorkspaceSession.LinuxProot`.
The flow:

1. `start()` rejects non-`LinuxProot` sessions with
   `UnsupportedKind`.
2. `start()` checks the current state (`isStartable`); if
   not, returns `SessionAlreadyRunning`.
3. `start()` calls `DistroManager.findInstalled(id)` and
   `DistroManager.launcherFor(id)`. Either returning null is
   a typed error (`DistroNotInstalled` / `LauncherUnavailable`)
   and rolls the state to `Error`.
4. `start()` asks the launcher to build the command line,
   then calls `ProcessLauncher.start`. An `IOException` from
   the process launcher is `StartFailed` and rolls the
   state to `Error`.
5. On success, the runner records the `LaunchedProcess`,
   moves the state to `Running(pid, startedAtMs)`, and
   publishes a `RuntimeEvent.SessionStartedEvent`.
6. `stop()` looks up the handle, removes it atomically, calls
   its `stop()` callback, moves the state to `Stopped`, and
   publishes a `RuntimeEvent.SessionStoppedEvent`.

The `WindowsVmSessionRunner` (a future phase, 31+) will
delegate to the existing `WindowsVmManager` (Phase 22) for
the start / stop calls; the QEMU process handle is the
`LaunchedProcess` analog.

### 5. Three new `RuntimeEvent` variants

- `SessionStartedEvent(atMs, workspaceId, sessionId, kind, launcherKind, pid)`
- `SessionStoppedEvent(atMs, workspaceId, sessionId, exitCode)`
- `SessionStartFailedEvent(atMs, workspaceId, sessionId, kind, error)`

These extend the existing sealed class with no breaking
change — the `when` is exhaustive-by-default and the new
variants are additive.

## Why this shape

- **No `ProcessBuilder` in the runner.** The runner is
  JVM-testable end-to-end; the only `IOException` path is
  one the test fake can throw on demand. 12 unit tests pin
  the start / stop / state / listActive / error / bus /
  thread-safety contract with no `ProcessBuilder` mock.

- **A small `ProcessLauncher` interface (one method).** Per
  the engineering rule "capture only the values the JVM-
  testable code reads": the runner needs the pid (for the
  `SessionState.Running`) and a way to stop the process.
  stdin/stdout/stderr wiring, environment propagation,
  and working directory are the *launcher* impl's job.
  The runner treats the launched process as a black box
  with `pid` and `stop()`.

- **A typed `SessionRunnerError` sealed class.** The UI
  branches on the kind ("show install button" vs
  "show snackbar" vs "show unsupported-kind error").
  Free-form strings would force string-matching in the
  UI.

- **Per-session state in a `ConcurrentHashMap`.** No
  read-modify-write race; concurrent `start()` / `stop()`
  on the same session see the latest state. A
  check-then-act lock is overkill for a single-key
  operation; `ConcurrentHashMap.compute` is enough.

- **State is published to the bus, not stored in the
  bus.** The bus is a notification seam, not a state
  store. The main-screen ViewModel + the audit log read
  the bus; the canonical state is in the runner.

## Consequences

- The `WorkspacesViewModel` (Phase 29) gains `startSession`
  / `stopSession` methods that delegate to the runner in
  a future phase.
- The `MainScreenViewModel` (Phase 28) gains a
  `runningSessionCount` field that reads the runner's
  `listActive()` in a future phase.
- The Hilt module (Phase 21) gains a `@Provides @Singleton`
  for `ProcessLauncher` (the Android impl) in a future
  phase.
- The Compose UI gains "Start" / "Stop" buttons on the
  workspace detail screen in a future phase.

## Alternatives considered

- **Wire `start` / `stop` directly into `WorkspaceManager`.**
  Rejected: the manager is a persistence orchestrator
  (Phase 24). Mixing in OS-process state muddles the
  concerns; the manager would also need a `ProcessLauncher`
  dependency, and the persistence layer should not depend
  on a fork-and-exec seam.

- **A single `SessionRunner` with a `when (kind)` switch.**
  Rejected: a future Windows VM runner will need a very
  different shape (delegates to `WindowsVmManager`,
  no `ProcessLauncher`, no `LinuxProot` build path). A
  per-kind impl composition is the same cost on the
  call-site side (`runner.start(ws, session)`) and keeps
  each impl focused.

- **A coroutine-based `StateFlow<SessionState>` per
  session.** Rejected: a flow-per-session is the right
  shape for the UI, but the runner's contract is
  synchronous (`Result<SessionState>`). The UI adapter
  in a future phase projects the in-memory map onto a
  flow; the runner itself does not own the flow.

## Revisit triggers

- A session kind beyond `LinuxProot` and `WindowsVm`
  appears (e.g. Android-side microVM, WebAssembly, GPU
  passthrough). Add a new `SessionRunner` impl and
  register it in the dispatch table.
- The runner needs to be observable from a coroutine
  (e.g. the UI binds a `StateFlow` directly). Add a
  `states: StateFlow<Map<SessionKey, SessionState>>` to
  the interface; the in-memory map already backs it.
- The runner needs to survive process restarts (i.e. a
  session that was running before the app died should
  be detected and rolled to `Stopped` on next launch).
  Add a persistence seam; today's design is in-memory
  only.
