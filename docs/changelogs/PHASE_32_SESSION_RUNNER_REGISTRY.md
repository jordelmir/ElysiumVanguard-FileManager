# Phase 32 — SessionRunnerRegistry (dispatch by session kind)

Date: 2026-07-17
Status: **Shipped** — `assembleDebug` green, 1317 tests, 0 failures, 2 skipped.

## What landed

The runtime's `SessionRunner` surface is now
fully wired. Phases 30 and 31 shipped the two
impls (`LinuxProotSessionRunner`,
`WindowsVmSessionRunner`); Phase 32 ships the
dispatch facade that lets the UI call a single
`SessionRunner` reference and have the right
impl handle each `WorkspaceSession.kind`.

Until Phase 32, the UI had to know which runner
handled which kind — a `when (session.kind)`
switch in the call site. The switch belongs in a
dedicated class, not the call site: a future
kind beyond `LinuxProot` and `WindowsVm`
(Android-side microVM, WebAssembly, GPU
passthrough) is a runtime-configuration concern,
not a UI concern.

### Files

**Production (1 new):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/runner/SessionRunnerRegistry.kt`
  — the dispatch facade. Holds a reference to
  the linux runner + the windows runner; routes
  by `WorkspaceSession.kind`. For
  `state(workspaceId, sessionId)`, asks the
  linux runner first; falls through to the
  windows runner if the linux runner returns
  `Idle`. For `listActive()`, merges the two
  runners' active-session lists and sorts by
  `SessionState.Running.startedAtMs` ascending.
  The registry is itself a `SessionRunner`, so it
  slots into the same call sites as the
  individual impls.

**Tests (1 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/runner/SessionRunnerRegistryTest.kt`
  — 10 unit tests covering:
  - `start` with a `LinuxProot` session routes to
    the linux runner, NOT the windows runner
  - `start` with a `WindowsVm` session routes to
    the windows runner, NOT the linux runner
  - `stop` routes by kind (both directions)
  - `state` returns `Idle` when neither runner has
    the session
  - `state` prefers the linux runner's view when
    it is non-Idle
  - `state` falls back to the windows runner when
    the linux runner is `Idle`
  - `listActive` merges both runners and sorts by
    `startedAtMs` ascending
  - `activeCount` matches `listActive().size`
  - thread-safety under 4 × 20 concurrent mixed-
    kind starts (80 total starts, all routed
    correctly, no lost writes)

**ADR (1 new):**

- `docs/adr/ADR-019-session-runner-registry.md` —
  context, decision (the registry shape, the
  "facade, not a `when` switch" rationale, the
  "ask both runners for `state()`" rationale, the
  "merged + sorted `listActive()`" rationale),
  consequences, alternatives considered, revisit
  triggers.

### Bug fix during this phase

The first test run failed three assertions in
the `state` tests. Root cause: the
`RecordingRunner` test fake's `state()` method
returned `stateMap[sessionId] ?: SessionState.Idle`,
but the test configured the runner's default
state via the constructor (`RecordingRunner(SessionState.Running(...))`)
and never called `start()`, so `stateMap` was
empty. Fix: `state()` now returns
`stateMap[sessionId] ?: stateForStart` (the
constructor's default state). All 10 tests pass.

This is a test-discipline gotcha: when a fake
has a constructor parameter that is meant to be
the default for `state()`, the fake's `state()`
must consult it. A production runner's `state()`
looks up an in-memory map populated by `start()`
and `stop()`; a test fake needs to honour the
same shape, including the "no entry → default"
path.

## Why this matters

Master order §24: the user taps a session in a
workspace, the session starts. The UI's call site
is a single `runner.start(workspace, session)`
invocation. The kind-to-impl mapping is hidden
behind the registry.

The 10 tests pin the dispatch logic end-to-end
with no real runners, no real sessions, and no
real workspaces. The registry is JVM-testable
because:

- the two runner references are constructor
  parameters (the tests pass `RecordingRunner`
  fakes),
- the dispatch is a `when (session.kind)` switch
  the compiler exhaustiveness-checks, and
- the merged `listActive()` is a plain list
  concatenation + sort.

## What the registry does

| Concern | Where it lives |
|---|---|
| The kind-to-impl mapping | `runnerFor(session): SessionRunner` (private, `when` over the sealed hierarchy) |
| The start / stop dispatch | Forwards to `runnerFor(session).start(stop)` |
| The state lookup | Asks linux first, falls through to windows on `Idle` |
| The active-sessions merge | Concatenates the two lists, sorts by `startedAtMs` ascending |
| The thread-safety | Inherited from the two underlying runners (both are `ConcurrentHashMap`-backed) |

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `SessionRunnerRegistryTest` | 10 | 0 |
| **Project total** | **1317** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

The next phase is **wire the runners into the
`WorkspacesViewModel`** (Phase 29 gains
`startSession` / `stopSession` methods that
delegate to the registry). The follow-ups after
that:

- `runningSessionCount` field in
  `MainScreenViewModel` (Phase 28) that reads
  `registry.listActive().size`,
- the Compose UI that consumes both ViewModels
  (the only piece of the runtime still inside
  the `features/` package, and the only piece
  that needs an Android-instrumented test for
  end-to-end coverage).
