# Phase 119 — Real `waitFor` on `LinuxProotSessionRunner.stop()`

**Status:** Shipped
**Commit:** (this commit)
**Tests:** +4 added (18 total in `LinuxProotSessionRunnerTest`)
**Suite:** 3818 tests, 1 pre-existing flake (`FoundryRepositoryContractTest`,
unchanged from Phase 118). The previous round-118 in-progress cleanup of
`FoundryServiceRepositoryIntegrationTest` (the `setMain` / `runTest` / test
dispatcher scaffolding) is still uncommitted — it was correctly identified as
the canonical fix for the cross-test `UncaughtExceptionsBeforeTest` leak and
is staged separately to avoid a regression in the
`RootedModeViewModel` production constructor.

## Problem

`LinuxProotSessionRunner.stop()` is the inverse of `start()`. The runner
launches a real host OS process (`proot`, `chroot`, etc.) via
`ProcessLauncher.start(...)` and stashes the returned `LaunchedProcess` in a
per-session map. `stop()` removes the handle, invokes its `stop()` callback,
and publishes a `RuntimeEvent.SessionStoppedEvent` carrying the process's
**exit code**.

The exit code was computed as:

```kotlin
val exitCode = (current as? SessionState.Running)?.let { 0 } ?: -1
```

That's the same anti-pattern Phase 117 fixed in
`ProcessLauncherDiskImageBackend` and `ProcessLauncherPackageInstaller`: the
exit code is hardcoded to `0` when the session was `Running` and `-1` when it
was not. The real OS exit code was never consulted. Concretely:

  - `qemu-img convert` failing on a malformed disk image → reported as `0`
    (the runner thought the mount succeeded)
  - `mount -o ro,loop /path/to/rootfs` failing on a corrupt filesystem →
    reported as `0`
  - `proot apt-get install` killed by a sandbox violation → reported as `0`
    instead of the real `100` (apt) or `137` (SIGKILL)
  - The 5-second `SIGTERM` grace period ending in a `SIGKILL` (typical exit
    code `128 + 9 = 137`) → reported as `0`

The downstream consumer is the `RuntimeEvent.SessionStoppedEvent` payload, which
the UI surfaces in the runtime status card and the audit log. A persistent
`exitCode = 0` for a session that died kills a real `127` from a `command not
found` masks the failure from the operator and the audit trail.

## Fix

`LaunchedProcess` (Phase 117) already exposes a `waitFor: () -> Int` callback
that returns the real OS exit code. `AndroidProcessLauncher` wires it to
`Process.waitFor()`. The runner was simply not consulting it.

The new `stop()` is:

```kotlin
val exitCode = try {
    handle?.waitFor?.invoke() ?: -1
} catch (t: Throwable) {
    -1
}
```

Three properties:

  1. **Reads the real exit code.** `handle` is non-null iff the session was
     `Running` (the `Starting` branch never reaches this point; `Idle` fails
     the `isStoppable()` check above). For `Running` sessions, the runner
     now reports whatever the OS returned — including the `137` from a
     `SIGKILL`, the `143` from a `SIGTERM`, the `100` from an apt failure,
     the `127` from a missing command.

  2. **Defensive fallback.** A `ProcessLauncher` whose `waitFor` throws (e.g.
     a custom backend that races on the OS pid — the pid was reaped by a
     sibling signal handler before we got there) must not crash the runner.
     The `try { ... } catch (t: Throwable) { -1 }` is the same canonical
     pattern as the other Phase 117 callers: the worst case is a sentinel
     exit code (`-1`), the runner never propagates a callback failure to
     the UI.

  3. **No API change.** `LaunchedProcess` was already extended in Phase 117.
     The runner's public surface (`start` / `stop` / `state` / `listActive`)
     is unchanged. All existing tests that call `stop()` on a session they
     previously started keep passing — the `RecordingProcessLauncher` now
     defaults its `waitFor` callback to return `0` (matching the previous
     hardcoded behavior) for backward compatibility.

## Test coverage

Four new tests in `LinuxProotSessionRunnerTest.kt`:

  1. **`stop reads the real OS exit code from the process launcher's waitFor
     and publishes it`** — happy path; `waitForExitCode = 0` is published
     verbatim, `waitForCount` is `1` (the runner consults `waitFor` exactly
     once per session).
  2. **`stop propagates a non-zero exit code (SIGTERM = 143) from waitFor`** —
     `143 = 128 + 15 (SIGTERM)`, the typical exit code a proot session emits
     when killed by a stop signal. This was previously reported as `0`.
  3. **`stop propagates an unusual exit code verbatim (137 = SIGKILL)`** —
     `137 = 128 + 9 (SIGKILL)`. The test pins the verbatim behavior: no
     translation, no clamping.
  4. **`stop falls back to exit code -1 when waitFor throws and still
     publishes the event`** — defensive path. The test instantiates a
     `ThrowingWaitForProcessLauncher` whose `waitFor` callback throws
     `IllegalStateException("pid reaped")`. The runner must (a) still
     return `Result.success(Stopped)`, (b) still publish the
     `SessionStoppedEvent`, (c) record the exit code as `-1`.

The `RecordingProcessLauncher` test fake was extended with a
`waitForExitCode: Int = 0` parameter (default keeps the existing 14 tests
green) and a `waitForCount: AtomicInteger` to count invocations. A new
`ThrowingWaitForProcessLauncher` test fake exercises the defensive path.

## Compatibility

  - The fix mutates an internal computation; no production API change.
  - `LaunchedProcess` was already extended in Phase 117 — the new `waitFor`
    parameter has a default of `{ -1 }` so legacy test fakes that only
    supply `pid` + `stop` still compile.
  - The `RecordingProcessLauncher` test fake's new `waitForExitCode`
    parameter defaults to `0` (matching the previous hardcoded behavior) —
    all 14 pre-existing tests in `LinuxProotSessionRunnerTest` still pass
    without modification.
  - Production behavior changes: the `RuntimeEvent.SessionStoppedEvent`
    payload now carries the real OS exit code instead of `0` / `-1`. The
    `SessionStoppedEvent` schema is unchanged (`exitCode: Int`).

## Files

  - `app/src/main/java/com/elysium/vanguard/core/runtime/runner/LinuxProotSessionRunner.kt` — `stop()` reads `handle.waitFor()` instead of hardcoding.
  - `app/src/test/java/com/elysium/vanguard/core/runtime/runner/LinuxProotSessionRunnerTest.kt` — `RecordingProcessLauncher` gains `waitForExitCode` + `waitForCount`; new `ThrowingWaitForProcessLauncher`; 4 new tests.
