# ADR-019 — SessionRunnerRegistry (dispatch by session kind)

- Status: Accepted
- Date: 2026-07-17
- Phase: 32 (SessionRunnerRegistry)
- Deciders: Mavis (Elysium Vanguard runtime)

## Context

Phases 30 and 31 shipped the `SessionRunner` interface
(ADR-017) plus two implementations:

- `LinuxProotSessionRunner` for
  `WorkspaceSession.LinuxProot`,
- `WindowsVmSessionRunner` for
  `WorkspaceSession.WindowsVm`.

The `SessionRunner` interface is the orchestrator
the UI calls when the user taps "Start session" or
"Stop session". The two impls live in different
files; each rejects the other kind with
`UnsupportedKind`. Until Phase 32, the UI had to
know which runner handled which kind — a
`when (session.kind)` switch in the call site.

That is a small surface today, but it is the wrong
shape. A future kind beyond `LinuxProot` and
`WindowsVm` (Android-side microVM, WebAssembly,
GPU passthrough) would force every call site to
add a new branch. The kind-to-impl mapping is
runtime configuration, not call-site logic.

## Decision

We ship a dispatch facade — `SessionRunnerRegistry`
— that:

- is itself a `SessionRunner` (so it slots into
  the same call sites; the UI's call shape does
  not change),
- holds a reference to the linux runner + the
  windows runner (the constructor takes both),
- dispatches by `WorkspaceSession.kind`:
  - `LinuxProot` → `LinuxProotSessionRunner`
  - `WindowsVm` → `WindowsVmSessionRunner`
- for `state(workspaceId, sessionId)`, asks the
  linux runner first; if it returns `Idle`, falls
  through to the windows runner. (In practice,
  sessionIds are unique within a workspace, so
  only one of the two runners can hold a non-Idle
  state for any given id — the first non-Idle
  wins.)
- for `listActive()`, merges the two runners'
  active-session lists and sorts by
  `SessionState.Running.startedAtMs` ascending so
  the UI sees a stable order.

The registry is `Context`-free and JVM-testable
end-to-end. Tests pass two `RecordingRunner`
fakes; production wires the real
`LinuxProotSessionRunner` and
`WindowsVmSessionRunner`.

## Why this shape

- **A facade, not a `when (kind)` switch in the
  caller.** The kind-to-impl mapping is runtime
  configuration. The UI should not need to know
  which impl handles which kind. A new kind adds
  a new constructor parameter; no call-site code
  changes.

- **The registry is itself a `SessionRunner`.** A
  caller that already has a `SessionRunner`
  reference (the `WorkspacesViewModel`, the
  `MainScreenViewModel`) can swap the real impl
  for the registry without changing the call
  shape. The UI's `runner.start(workspace,
  session)` call does not care which runner
  handled it.

- **A `state()` lookup that asks both runners.**
  A `(workspaceId, sessionId)` pair uniquely
  identifies a session, but the registry has no
  way to know which kind without a kind hint.
  Asking both is O(1) and unambiguous: the first
  non-Idle wins. (If a future kind adds a third
  runner, the `state()` lookup remains
  two-arg-and-O(N) over the runner count, which
  is fine for N = 2 or 3.)

- **A merged `listActive()` sorted by
  `startedAtMs`.** The UI wants a single list of
  "every active session across every kind",
  stable-ordered so a follow-up frame does not
  shuffle the rows.

## Consequences

- The `WorkspacesViewModel` (Phase 29) gains
  `startSession(workspace, session)` /
  `stopSession(workspace, session)` methods that
  delegate to the registry in a follow-up phase.
- The `MainScreenViewModel` (Phase 28) gains a
  `runningSessionCount` field that reads
  `registry.listActive().size` in a follow-up
  phase.
- The Hilt module (Phase 21) gains a `@Provides
  @Singleton` for `SessionRunnerRegistry` in a
  follow-up phase.
- A future kind (Android-side microVM,
  WebAssembly, GPU passthrough) is a single new
  `SessionRunner` impl + a new constructor
  parameter on the registry; no call-site code
  changes.

## Alternatives considered

- **A `when (kind)` switch in the
  `WorkspacesViewModel`.** Rejected: the switch
  belongs in a dedicated class, not the
  ViewModel. The ViewModel is the action layer
  for the workspace UI; the kind-to-impl mapping
  is runtime configuration.

- **A single `SessionRunner` with a `when (kind)`
  switch inside.** Rejected: a future kind
  beyond `LinuxProot` and `WindowsVm` would drag
  in a third `if` branch in the same class. Per-
  kind impl composition is the same cost on the
  call-site side and keeps each impl focused.

- **A `Map<WorkspaceSession.SessionKind,
  SessionRunner>` lookup.** Rejected: the map
  adds a layer of indirection that the registry
  does not need. The `when` in `runnerFor` is
  exhaustive over the sealed hierarchy; a future
  kind is a compile error in the `when`, not a
  silent `null` from a `Map.get`.

## Revisit triggers

- A session kind beyond `LinuxProot` and
  `WindowsVm` appears. Add a new `SessionRunner`
  impl and a new constructor parameter on the
  registry.
- The registry needs to be observable from a
  coroutine (e.g. the UI binds a `StateFlow`
  directly). Add a
  `states: StateFlow<Map<SessionKey, SessionState>>`
  to the `SessionRunner` interface; the registry
  merges the two underlying flows.
- The `state(workspaceId, sessionId)` lookup
  becomes hot (called on every UI frame). Add a
  per-session kind cache so the registry does
  not ask both runners every time.
