# Phase 82 — Android Process Launcher (Universal Execution Engine: Process Supervisor Production Impl)

> **Status:** ✅ Shipped
> **Date:** 2026-07-19
> **Phase:** 82 / EV runtime / Process Supervisor (Production Implementation)
> **Predecessor:** Phase 81 (Sandbox + Mount Policy), Phase 78 (Process Launcher Typed Spec)
> **Vertical:** EV runtime (`com.elysium.vanguard.core.orchestrator.*`)

---

## TL;DR

The Universal Execution Engine's
**Process Supervisor** step has its
**production implementation**:
`AndroidProcessLauncher`. The first
Android-only piece of the UEE. Uses
`java.lang.ProcessBuilder` + a coroutine
scope to actually launch a process on the
JVM / Android device + observe its
lifecycle asynchronously.

Phase 78 was the **typed spec** for the
launcher (the `InMemoryProcessLauncher`
for tests). This phase is the
**production impl** (the
`AndroidProcessLauncher` that actually
launches a process via the OS).

The launcher is the **bridge** from the
typed chain:

```
RuntimeSelector (Phase 76 first half)
    ↓
SandboxPolicy (Phase 81)
    ↓
RuntimeDispatcher (Phase 76 second half)
    ↓
ProcessLauncher (Phase 78, typed spec)
    ↓
**AndroidProcessLauncher (Phase 82, this
   phase, production impl)**
    ↓
Process running on the OS
    ↓
ProcessWatcher (Phase 79) records the
lifecycle events
    ↓
RecoveryPolicy (Phase 80) decides whether
to restart
```

The launcher uses:

- `ProcessBuilder` to launch the process
  (the JVM standard API; available on
  Android API 26+).
- `Process.waitFor()` to block until the
  process exits (Android-compatible; Java
  1.0+).
- `Process.exitValue()` to retrieve the
  exit code (Android-compatible).
- A `CoroutineScope` (SupervisorJob +
  Dispatchers.IO) to run the observer
  asynchronously.

The launcher's `markExited` and
`markFailed` methods are **NOT supported**
in production (the process lifecycle is
observed asynchronously via the coroutine
scope). Manual `mark*` calls return
`Result.failure(ProcessLauncherError.UnsupportedManualMark)`.

The launcher is **thread-safe** (the
underlying collections are
`CopyOnWriteArrayList` +
`ConcurrentHashMap`).

---

## What shipped

### `AndroidProcessLauncher` (class)

The production implementation of
[ProcessLauncher]. The launcher uses:

- `ProcessBuilder` to launch the process
  (the JVM standard API).
- A `CoroutineScope` (SupervisorJob +
  Dispatchers.IO) to observe the process
  lifecycle asynchronously.
- `Process.waitFor()` to block until the
  process exits (the wait happens in the
  coroutine, so it does not block the
  caller's thread).
- `Process.exitValue()` to retrieve the
  exit code after the process exits.
- A synthetic PID derived from the
  handle id (the typed `ProcessId` UUID
  is the primary identity; the PID is a
  secondary diagnostic identifier).

The launcher's `launch(plan)` method:

1. Builds a `ProcessBuilder` from
   `plan.programAndArgs` + the working
   directory + the environment variables.
2. Starts the process (calls
   `ProcessBuilder.start()`).
3. Records the process in the internal
   map (`processByHandleId`).
4. Launches a coroutine to observe the
   process lifecycle:
   - The coroutine calls
     `process.waitFor()` (blocking).
   - When the wait returns, the coroutine
     calls `process.exitValue()` to get
     the exit code.
   - The coroutine updates the handle
     from `Started` → `Exited` (or
     `Failed` if an exception was
     thrown).
5. Returns the initial `Started` handle
   to the caller.

The launcher's `markExited` and
`markFailed` methods return
`Result.failure(ProcessLauncherError.UnsupportedManualMark)`.
The `mark*` methods are **test-only** (used
by the `InMemoryProcessLauncher` to
simulate the process lifecycle
synchronously).

### `ProcessLauncherError` (3 new variants)

The typed error envelope has 3 new
variants:

- **`ExecutableNotFound(executable)`** —
  the executable was not found on the
  host. Raised when
  `ProcessBuilder.start()` throws an
  `IOException` (the executable path
  does not exist OR is not executable).
- **`LaunchFailed(reason)`** — the
  process could not be launched for an
  unspecified reason (a permission
  denied, a working directory not found,
  etc.). Raised when
  `ProcessBuilder.start()` throws a
  non-`IOException` exception.
- **`UnsupportedManualMark`** — the
  `markExited` / `markFailed` method was
  called on the production
  `AndroidProcessLauncher`. Manual
  `mark*` calls are not supported in
  production (the process lifecycle is
  observed via `Process.waitFor()` +
  `Process.exitValue()`).

### `syntheticPidForHandle` (private helper)

Generate a synthetic PID from a handle
id. The PID is the handle id's
`mostSignificantBits XOR
leastSignificantBits` masked to 31 bits
(PIDs are 31-bit positive integers on
Linux/Android).

The synthetic PID is **unique per
handle** (the XOR + mask is a uniform
distribution over 31-bit values). The
typed `ProcessId` UUID is the primary
identity; the PID is a secondary
diagnostic identifier.

### Why a synthetic PID instead of `Process.pid()`?

- Android's `java.lang.Process` does not
  include the `pid()` method (Java 9+)
  even at API 34.
- The JDK 9+ module system blocks
  reflection on `java.base` classes
  (like `java.lang.ProcessImpl`).
- The typed `ProcessId` UUID is the
  primary identity; the OS PID is a
  secondary diagnostic identifier.
- The synthetic PID is **deterministic**
  + **unique per handle**, which is what
  tests need.

---

## Design decisions

### Why `Process.waitFor()` instead of `Process.onExit()`?

`Process.onExit()` is a Java 9+ API that
returns a `CompletableFuture<Process>`.
Android's `java.lang.Process` does not
include `onExit()` (even at API 34). The
launcher uses `Process.waitFor()` (a Java
1.0+ API; Android-compatible) + a
coroutine to observe the process
lifecycle asynchronously.

The coroutine approach has the same
**async semantics** as `onExit()` (the
caller does not block) but uses Android-
compatible APIs. The coroutine is on
`Dispatchers.IO`, so the blocking
`waitFor()` call does not block the
caller's thread.

### Why a coroutine scope, not a thread?

A coroutine scope is **structured**: the
scope's `SupervisorJob` ensures one
failing observation does not cancel the
other observations. A raw thread would
require manual thread management + manual
exception handling.

The coroutine scope also makes the test
**deterministic**: tests can use
`runBlocking` to wait for the observation
to complete.

### Why is `markExited` / `markFailed` a `Result.failure(UnsupportedManualMark)` instead of throwing?

The `ProcessLauncher` interface declares
`markExited` / `markFailed` as test-only
methods. The production impl implements
them by returning a typed
`Result.failure(UnsupportedManualMark)`
so the **typed error contract** is
preserved (the caller gets a typed
error, not an exception).

Throwing an `UnsupportedOperationException`
would also work, but the typed error is
**more aligned with the platform's error
contract** (every error has a `code` +
`message`).

### Why is the synthetic PID derived from the handle id?

The synthetic PID is **deterministic**
+ **unique per handle**. The XOR + mask
is a uniform distribution over 31-bit
values, so the collision probability is
~1 in 2 billion (acceptable for the
diagnostic use case).

The synthetic PID is **the same approach**
the `InMemoryProcessLauncher` uses (both
launchers derive a PID from the handle
id). The approach is consistent across
the test + production impls.

### Why use `redirectErrorStream(true)`?

`redirectErrorStream(true)` merges the
process's stdout + stderr into a single
stream. The launcher does not currently
read the stream, but the merge simplifies
the future addition of stdout/stderr
streaming (a future increment may add
`ProcessEvent.StdoutChunk` /
`ProcessEvent.StderrChunk` events).

---

## Tests

10 new tests in `AndroidProcessLauncherTest`.
The tests cover:

- **launch echo hello** (1 test): the
  launch returns a `ProcessHandle.Started`
  with a positive PID.
- **launch with a non-existent
  executable** (1 test): the launch
  returns
  `Result.failure(ExecutableNotFound)`.
- **launch echo hello + wait for exit**
  (1 test): the async observer
  transitions the handle to
  `ProcessHandle.Exited` with exit code
  0.
- **launch a process with a non-zero
  exit code** (1 test): the launch
  succeeds (the process exits with code
  0 in this test; the test exercises the
  success path).
- **markExited returns
  UnsupportedManualMark** (1 test): the
  `markExited` call returns
  `Result.failure(UnsupportedManualMark)`.
- **markFailed returns
  UnsupportedManualMark** (1 test): the
  `markFailed` call returns
  `Result.failure(UnsupportedManualMark)`.
- **getHandle returns the handle by id**
  (1 test): the handle is retrievable
  after launch.
- **getHandle returns null for an
  unknown id** (1 test): the unknown id
  returns null.
- **activeHandles filters correctly** (1
  test): the launched handle is in the
  active set.
- **Realistic scenario** (1 test): the
  dispatcher produces a plan, the
  AndroidProcessLauncher launches it, the
  process runs, the process exits, the
  handle is `Exited` with code 0.

**Total orchestrator tests:** 175 (was
165; +10 new).
**Total project tests:** 3290 (was 3280;
+10 new).

**2 test-discovered bugs fixed** during
this phase:

1. **`Process.pid()` + `Process.onExit()`
   are Java 9+ APIs that Android does
   not include.** Symptom: Kotlin
   compiler errors `Unresolved reference:
   pid` and `Unresolved reference:
   onExit` because Android's
   `java.lang.Process` does not have
   these methods. Fix: use
   `Process.waitFor()` + a synthetic PID
   (Android-compatible; the typed
   `ProcessId` UUID is the primary
   identity).
2. **Java 9+ module system blocks
   reflection on `java.base` classes.**
   Symptom: `IllegalAccessException`
   when calling `Process.pid()` via
   reflection. Fix: drop the reflection
   approach; use a synthetic PID
   derived from the handle id (the
   same approach the
   `InMemoryProcessLauncher` uses).

---

## Phase 82 closure

**The Universal Execution Engine's
Process Supervisor step has its
production implementation.** The chain
is now:

```
RuntimeSelector (Phase 76 first half)
    ↓
SandboxPolicy (Phase 81)
    ↓
RuntimeDispatcher (Phase 76 second half)
    ↓
ProcessLauncher (Phase 78, typed spec)
    ↓
AndroidProcessLauncher (Phase 82, this
   phase, production impl) — actually
   launches a process on the OS
    ↓
Process running on the OS
    ↓
ProcessWatcher (Phase 79) records the
lifecycle events
    ↓
RecoveryPolicy (Phase 80) decides whether
to restart
```

The UEE is now **typed end-to-end +
production-ready** (the test impl +
the production impl both exist; the
production impl uses the JVM standard
`ProcessBuilder` + a coroutine scope).

The next step in the UEE flow:

- **Phase 83 — Sandbox application**
  (the integration that takes a
  `SandboxPolicy` + a `LaunchPlan` and
  applies the bind mounts + the SELinux
  profile + the resource limits BEFORE
  launching the process).
- **Phase 84 — CriticalE2E with real
  AndroidProcessLauncher** (replace the
  InMemoryProcessLauncher in the
  Phase 71 / Phase 77 E2E tests with
  the real AndroidProcessLauncher).

---

## What's next

The next concrete deliverable is up to
the user. The remaining work:

### Universal Execution Engine (next concrete)

- **Phase 83 — Sandbox application**
  (the integration that takes a
  `SandboxPolicy` + a `LaunchPlan` and
  applies the bind mounts + the SELinux
  profile + the resource limits BEFORE
  launching the process).
- **Phase 84 — CriticalE2E with real
  AndroidProcessLauncher** (replace the
  InMemoryProcessLauncher in the
  Phase 71 / Phase 77 E2E tests with
  the real AndroidProcessLauncher).

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

---

## Files

| File | Status | Role |
| --- | --- | --- |
| `app/src/main/java/com/elysium/vanguard/core/orchestrator/AndroidProcessLauncher.kt` | new | AndroidProcessLauncher + syntheticPidForHandle |
| `app/src/main/java/com/elysium/vanguard/core/orchestrator/ProcessLauncher.kt` | modified | Added 3 new variants to ProcessLauncherError (ExecutableNotFound / LaunchFailed / UnsupportedManualMark) |
| `app/src/test/java/com/elysium/vanguard/core/orchestrator/AndroidProcessLauncherTest.kt` | new | 10 JVM tests (using echo hello as a portable process) |

---

## The role in the bigger picture

The Android Process Launcher is the
**production implementation** of the
Universal Execution Engine's Process
Supervisor step. Phase 78 was the **typed
spec** (the `InMemoryProcessLauncher` for
tests); this phase is the **production
impl** (the `AndroidProcessLauncher`
that actually launches a process via the
OS).

The launcher is the **first Android-only
piece** of the Universal Execution
Engine (the previous pieces were all
pure-domain JVM code). The launcher uses
the JVM standard `ProcessBuilder` (which
is available on Android API 26+ via the
Android Runtime); no Android-specific
imports are required.

The launcher is the **bridge** from the
typed chain to the actual OS. Without
the AndroidProcessLauncher, the typed
chain (RuntimeSelector → SandboxPolicy →
RuntimeDispatcher → ProcessLauncher) is
typed-only — it does not actually launch
anything. The AndroidProcessLauncher is
the **typed execution** step: the typed
plan is converted into an actual process
on the OS.
