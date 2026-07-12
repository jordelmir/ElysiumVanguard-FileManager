# ADR-001: Runtime backend abstraction

- Status: Accepted
- Date: 2026-07-12
- Owners: Elysium Vanguard runtime
- Governing order: Universal Computing Fabric, phases 0–13

## Context

The application currently mixes Compose navigation, Android services, distro
installation, PRoot command construction and terminal process ownership inside
the `:app` module. The active terminal launches a `ProcessBuilder` and exposes
pipe semantics. Future Linux, VM, WinLayer and remote runtimes cannot safely be
added by branching this implementation from UI code.

The runtime domain must represent what the app can actually execute, survive
Android lifecycle changes and keep unsupported capabilities unavailable rather
than simulated.

## Decision

Introduce a platform-neutral runtime domain before extracting Gradle modules.
Its public vocabulary will include:

- opaque `RuntimeId`, `SessionId`, `DistroId` and `WorkspaceId` values;
- immutable `RuntimeSpec`, `SessionSpec`, `CapabilityProfile` and resource
  limits;
- `RuntimeBackend`, `RuntimeSession`, `RuntimeRegistry` and `SessionManager`
  contracts;
- typed lifecycle events, diagnostic evidence, `RuntimeError` and
  `ExitReport`;
- the validated state machine `Created → Validating → Preparing → Starting →
  Running`, with explicit suspend, recovery, stopping, stopped and failed
  transitions.

The domain will not import Android, Compose, JNI, PRoot, Wine or a concrete VM
API. Android services own process lifetime through adapters. UI observes state
and issues typed intents. Native code is reached through a narrow backend
adapter.

Backends register their real capabilities after probing the device. A missing
capability is an unavailable action with a reason and repair path; it is never
substituted by a bitmap, placeholder process or success-shaped status.

The first backend is PRoot Linux ARM64. Existing classes remain behind adapters
until the new contract passes physical acceptance. Module extraction follows
API stability; it is not a prerequisite for the vertical slice.

## Invariants

1. Session transitions are validated and serialized.
2. `stop` is idempotent and produces exactly one final `ExitReport`.
3. Arguments and environment are structured values, never concatenated shell
   text.
4. A backend owns every PID, process group, FD, socket, mount/bridge and
   temporary path it creates.
5. Session ownership outlives an Activity and does not hold an Activity
   reference.
6. Capability claims are based on probes and acceptance evidence.
7. PRoot is process/filesystem translation, not a security boundary or VM.

## Alternatives considered

### Keep adding behavior to the current terminal ViewModel

Rejected. UI lifecycle and process lifecycle would remain coupled, backends
would duplicate state handling and testability would deteriorate.

### Expose one generic command-runner interface

Rejected. A command runner cannot describe display, network, filesystem,
clipboard, suspension, recovery or backend-specific capability limits.

### Split all target modules immediately

Rejected for the first slice. Moving unstable APIs across many Gradle modules
creates build churn without proving the runtime. Contracts stabilize in
packages first and are extracted incrementally.

## Consequences

- The first increment adds adapters and some duplication while migration is in
  progress.
- UI code becomes simpler and cannot infer success from process creation alone.
- Backends can evolve independently while sharing lifecycle and diagnostics.
- Tests can exercise state transitions and policies on the JVM without Android.
- Future module boundaries are visible but do not force a big-bang rewrite.

## Migration and rollback

1. Add domain types and transition tests.
2. Wrap the existing pipe launcher as an explicitly named legacy backend.
3. Add the native PTY PRoot backend behind a capability flag.
4. Route one distro session through the native backend.
5. Remove the legacy path only after device acceptance proves parity and
   cleanup.

Rollback disables the native capability and returns an actionable unavailable
state. It must not silently route PTY-dependent applications through pipes.

## Revisit triggers

- the domain requires Android types;
- a second backend cannot implement the contracts without backend-specific
  branching in UI;
- stop cannot prove process-group and FD cleanup;
- module extraction would remove more dependency edges than it adds.
