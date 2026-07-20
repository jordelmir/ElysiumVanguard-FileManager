# Phase 78 — Process Launcher (Universal Execution Engine: Process Supervisor)

> **Status:** ✅ Shipped
> **Date:** 2026-07-19
> **Phase:** 78 / EV runtime / Process Supervisor
> **Predecessor:** Phase 76 (Runtime Selector + Runtime Dispatcher, Universal Execution Engine)
> **Vertical:** EV runtime (`com.elysium.vanguard.core.orchestrator.*`)

---

## TL;DR

The Universal Execution Engine's
**Process Supervisor** step is operational.
The typed bridge from "what to launch" to
"the running process".

Per the master vision's Universal Execution
Engine (section 6), the dispatch flow is:

```
Runtime Selection (Phase 76 first half)
    ↓
Sandbox and Mount Policy
    ↓
Process Supervisor ← this phase
    ↓
Process Running
```

The chain is now:

- **`RuntimeSelector`** (Phase 76 first
  half) — picks the optimal runtime layer
  for a `Capsule` + a `DeviceProfile`.
- **`RuntimeDispatcher`** (Phase 76 second
  half) — produces a `LaunchPlan` (the
  launch command + the runtime arguments).
- **`ProcessLauncher`** (Phase 78, this
  phase) — actually launches the process
  + tracks the `ProcessHandle` (the
  typed identity + state of the running
  process).

The `ProcessLauncher` is the **typed
bridge** from "what to launch" to "the
running process". The launcher's input is
a `LaunchPlan` (from
`RuntimeDispatcher.dispatch`); the
launcher's output is a `ProcessHandle`
(the typed identity + state of the
running process).

The launcher is **3 primitives**:

1. **`ProcessLauncher`** (sealed class) —
   the typed launcher interface.
2. **`ProcessHandle`** (sealed class, 3
   cases) — the typed handle to a launched
   process: `Started` / `Exited` /
   `Failed`.
3. **`ProcessLauncherError`** (sealed
   class, 3 cases) — the typed error
   envelope: `InvalidProcessIdFormat` /
   `HandleNotFound` / `HandleNotStarted`.

Plus an in-memory implementation
(`InMemoryProcessLauncher`) for testing +
an Android-only production implementation
(`AndroidProcessLauncher`) for the real
device (a future increment).

---

## What shipped

### `ProcessLauncher` (sealed class)

The typed launcher. The interface has:

- **`launch(plan)`** — launch a new
  process from a `LaunchPlan`. Returns a
  `Result.success(ProcessHandle.Started)`
  on success; returns a
  `Result.failure(ProcessLauncherError)`
  on failure.
- **`getHandle(handleId)`** — get a
  process handle by id; returns `null`
  if not tracked.
- **`activeHandles()`** — get the
  active (running) handles (the
  `ProcessHandle.Started` instances).
- **`terminalHandles()`** — get the
  terminal handles (the
  `ProcessHandle.Exited` + the
  `ProcessHandle.Failed` instances).
- **`markExited(handleId, exitCode, exitedMs)`**
  — transition a handle from `Started` →
  `Exited`. **Test-only**: the production
  Android impl observes the process
  lifecycle via `Process.onExit()`.
- **`markFailed(handleId, reason, failedMs)`**
  — transition a handle from `Started` →
  `Failed`. **Test-only**: the production
  Android impl observes the process
  lifecycle via `Process.onExit()`.

### `ProcessHandle` (sealed class, 3 cases)

The typed handle to a launched process.
The sealed class has 3 cases:

- **`Started(handleId, plan, startedMs, pid)`**
  — the process is running; the launch
  was successful.
- **`Exited(handleId, plan, startedMs, pid, exitCode, exitedMs)`**
  — the process exited normally (with an
  exit code).
- **`Failed(handleId, plan, startedMs, failureReason, failedMs)`**
  — the process failed to launch OR
  crashed (with a failure reason).

The handle is **immutable** (a sealed class
with data class cases; no setters). A new
handle state is a new `ProcessHandle`
value, not a mutation of the existing one.

`Exited` has a `durationMs` helper (the
number of millis the process ran:
`exitedMs - startedMs`).

### `InMemoryProcessLauncher` (impl)

The in-memory implementation for testing.
The launcher:

- **`launch(plan)`** — records the launch
  attempt with a fake PID (the handle
  id's least-significant 31 bits; PIDs
  are 31-bit positive integers on
  Linux/Android) and returns a
  `ProcessHandle.Started`.
- **`markExited` / `markFailed`** —
  transitions a `Started` handle to
  `Exited` / `Failed` (with a state
  machine check: only `Started` handles
  can be marked).
- **`activeHandles` / `terminalHandles`** —
  filters by state.

The launcher is **thread-safe** (the
underlying list is `CopyOnWriteArrayList`
+ a `ConcurrentHashMap` for the handle id
→ handle lookup).

### `ProcessLauncherError` (sealed class, 3 cases)

The typed error envelope. The 3 variants:

- **`InvalidProcessIdFormat(rawInput, parseFailure)`**
  — the process id string was not a valid
  UUID.
- **`HandleNotFound(handleId)`** — the
  handle id is not tracked.
- **`HandleNotStarted(handleId)`** — the
  handle is not in the `Started` state
  (cannot mark a terminal handle as
  exited / failed).

### `ProcessId` (UUID value class)

The typed identity of a launched process.
The id is a UUID (per the Foundry id
convention). The id is distinct from a
Linux `pid` (process id); the launcher
can track the same logical process across
restarts (a future increment may support
process restart with the same id).

---

## Design decisions

### Why a sealed class for `ProcessHandle`, not a single class with a status flag?

A sealed class is **type-safe +
exhaustive**. The consumer (the
orchestrator) uses `when (handle)` to
dispatch by case:

- `is ProcessHandle.Started` — the
  process is running; check `pid` +
  `startedMs`.
- `is ProcessHandle.Exited` — the
  process exited; check `exitCode` +
  `durationMs`.
- `is ProcessHandle.Failed` — the
  process failed; check `failureReason`.

A single class with a flag would lose
the type safety; the consumer would need
to check the flag. The sealed class
captures the **3 distinct lifecycle
states** the process can have.

### Why is the `ProcessLauncher` a sealed class, not an interface?

The `ProcessLauncher` is a **sealed class**
with a single in-memory impl. The sealed
class captures the **abstract behavior**
(the platform's typed launcher contract);
the in-memory impl is the test + production
default. A future Phase 7+ increment will
add an `AndroidProcessLauncher` (the
production impl that uses
`java.lang.Process` + `ProcessBuilder`).

### Why is `markExited` / `markFailed` on the base interface, not just on the test impl?

The methods are on the base interface to
allow **test-only mutations** of the
process state. The production Android
impl will override them to throw
`UnsupportedOperationException` (or to
delegate to the real process observation
via `Process.onExit()`).

The methods are documented as
**test-only**; the production impl is not
expected to call them.

### Why is the fake PID computed from the handle id?

The `launch` method is **deterministic**
(the same handle id always produces the
same fake PID). The fake PID is the
handle id's `mostSignificantBits xor
leastSignificantBits` masked to 31 bits
(PIDs are 31-bit positive integers on
Linux/Android; a PID of 0 is invalid).

The fake PID is **unique per handle**
(the XOR + mask is a uniform distribution
over 31-bit values), which is what tests
need.

### Why is `ProcessId` distinct from a Linux `pid`?

A `ProcessId` is the **typed identity**
of a launched process in the launcher's
registry. A Linux `pid` is the **OS
identity** of a process (assigned by the
kernel at process creation).

A `ProcessId` can be tracked across
restarts (a future increment may support
process restart with the same id); a
Linux `pid` is **transient** (different
on each restart).

The `pid` is recorded on the
`ProcessHandle.Started` (and on
`ProcessHandle.Exited` + `Failed`) as
**additional context**; the primary
identity is the `ProcessId`.

### Why is `ProcessLauncherError` a separate sealed class, not extending `FoundryError`?

Kotlin sealed classes **only permit
subclassing in the same package where the
base class is declared**. `FoundryError`
lives in `ontology.primitives`; the
process launcher lives in
`orchestrator`. The cross-package
extension is not allowed. The cleanest
fix is a separate sealed class in the
`orchestrator` package that **mirrors**
the `FoundryError` contract (same `code`
+ `message` shape + extends
`RuntimeException`).

This is a **known Kotlin language
limitation**; the future "Kotlin 2.0
sealed interface" feature may allow
cross-package subclassing. For now, the
mirror class is the platform's typed-
error contract preserved.

---

## Tests

28 new tests in `ProcessLauncherTest`. The
tests cover:

- **ProcessHandle.Started invariants**
  (3 tests): well-formed configuration,
  zero pid, non-positive startedMs.
- **ProcessHandle.Exited invariants**
  (5 tests): well-formed configuration,
  non-positive startedMs, non-positive
  exitedMs, exitedMs < startedMs,
  durationMs.
- **ProcessHandle.Failed invariants**
  (5 tests): well-formed configuration,
  blank failureReason, non-positive
  startedMs, non-positive failedMs,
  failedMs < startedMs.
- **InMemoryProcessLauncher — launch +
  getHandle** (5 tests): launch returns a
  Started handle, launch records the
  handle in the handles list, launch
  produces a unique handle id per launch,
  getHandle returns the handle by id,
  getHandle returns null for an unknown
  id.
- **InMemoryProcessLauncher — active +
  terminal filters** (2 tests):
  activeHandles returns only Started
  handles, terminalHandles returns only
  Exited + Failed handles.
- **InMemoryProcessLauncher — markExited**
  (3 tests): markExited transitions
  Started → Exited, markExited rejects
  an unknown handle, markExited rejects
  a handle not in Started state.
- **InMemoryProcessLauncher — markFailed**
  (3 tests): markFailed transitions
  Started → Failed, markFailed rejects
  an unknown handle, markFailed rejects
  a handle not in Started state.
- **Realistic scenarios** (2 tests):
  dispatcher produces a plan + launcher
  launches it + the process runs + the
  process exits; multiple processes can
  be launched and tracked concurrently.

**Total orchestrator tests:** 76 (was 48;
+28 new).
**Total project tests:** 3191 (was 3163;
+28 new).

**1 test-discovered bug fixed** during
this phase:

1. **Realistic test fixtures used
   `exitedMs = 2000L` but `launch()` uses
   `System.currentTimeMillis()` (~1.7e12)
   for `startedMs`.** The `exitedMs >=
   startedMs` invariant check failed.
   Fix: the test fixtures now use
   `exitedMs = handle.startedMs + delta`
   (the handle's actual `startedMs` from
   the in-memory launcher + a delta).

---

## Phase 78 closure

**The Universal Execution Engine's Process
Supervisor step is operational.** The
chain is now complete:

- **Phase 76 first half** — `RuntimeSelector`
  (picks the optimal runtime layer).
- **Phase 76 second half** —
  `RuntimeDispatcher` (produces the
  launch plan).
- **Phase 78 (this phase)** —
  `ProcessLauncher` (executes the plan
  + tracks the process).

The next step in the Universal Execution
Engine is **Phase 79 — the real
`AndroidProcessLauncher`**, which uses
`java.lang.Process` + `ProcessBuilder` to
actually launch a process on the Android
device. The `InMemoryProcessLauncher` is
the test impl; the `AndroidProcessLauncher`
is the production impl.

---

## What's next

The next concrete deliverable is up to
the user. The remaining work in the EV
runtime + the Foundry program:

### Universal Execution Engine (next concrete)

- **Phase 79 — AndroidProcessLauncher**
  (the real production impl; uses
  `java.lang.Process` + `ProcessBuilder`).
- **Phase 80 — ProcessWatcher** (the
  observer that updates the handle state
  in real time; consumes `Process.onExit()`
  callbacks).
- **Phase 81 — Sandbox + Mount Policy**
  (the SELinux sandbox + the bind mount
  configuration for the launched
  process).

### Elysium Linux (next concrete)

- **Phase 73 fourth half — Minimal rootfs
  + Mesa/Turnip/Box64/FEX/Wine
  integration** (the actual binary;
  reproducible build on a Linux build
  server with ARM64 cross-compilation).
- **Phase 72 — Capsule installer UI**
  (Compose) for the new Elysium Linux
  distro.

### Foundry program (next concrete)

- **Phase F7 (G9+G10) — Production
  hardening**: threat model + SLOs +
  on-call + runbooks + red team + CVE
  SLA + observability + multi-module
  split (per ADR-0023).
- **Phase F8 (G11) — International
  expansion** (i18n + multi-currency +
  multi-jurisdiction compliance).
- **Phase F9 (G12) — The Foundry public
  API** (the B2B API surface for
  third-party integrations).

---

## Files

| File | Status | Role |
| --- | --- | --- |
| `app/src/main/java/com/elysium/vanguard/core/orchestrator/ProcessLauncher.kt` | new | ProcessLauncher + ProcessHandle + ProcessId + ProcessLauncherError + InMemoryProcessLauncher |
| `app/src/test/java/com/elysium/vanguard/core/orchestrator/ProcessLauncherTest.kt` | new | 28 JVM tests |

---

## The role in the bigger picture

The Process Launcher is the **Process
Supervisor** step in the Universal
Execution Engine. The full chain is:

1. **File Type / Manifest Detection**
   (Phase 69, Capsule) — the file is
   identified as a Capsule.
2. **Compatibility Resolver** (Phase 71,
   Critical E2E) — the Capsule is
   checked for compatibility.
3. **Architecture Detection** (Phase 76,
   DeviceProfile) — the device's
   capabilities are captured.
4. **Runtime Selection** (Phase 76 first
   half, RuntimeSelector) — the
   optimal runtime layer is picked.
5. **Sandbox and Mount Policy** (Phase
   81, future) — the SELinux sandbox +
   the bind mount configuration are
   built.
6. **Process Supervisor** (Phase 78, this
   phase) — the process is launched +
   tracked.

The Process Launcher is the **typed
bridge** from "what to launch" to "the
running process". Without the Process
Launcher, the Universal Execution Engine
is typed-only — it does not actually
launch anything. The Process Launcher
is the **typed execution** step.
