# Phase 117 — Real `waitFor` on `LaunchedProcess` (replace 60s polling)

**Status**: shipped (commit pending)
**Test delta**: +10 (1 pre-existing flake unchanged)

## What broke

The two production fileaction backends
(`ProcessLauncherDiskImageBackend` + `ProcessLauncherPackageInstaller`)
had a `waitForExit` helper that tried to detect process exit
with a 60-second polling loop:

```kotlin
private fun waitForExit(launched: LaunchedProcess, timeoutMs: Long = 60_000): Int {
    val attempts = (timeoutMs / 100).toInt()  // 600 attempts
    var i = 0
    while (i < attempts) {
        if (launched.pid <= 0) return 0  // <-- never true
        Thread.sleep(100)
        i++
    }
    launched.stop()
    return -1
}
```

The poll's exit-detection check
(`launched.pid <= 0`) was a no-op because
PIDs are assigned at fork time. The
helper would spin for 60 seconds, never
detect exit, and always return `-1`.

Net effect: every `qemu-img convert`,
`mount -o ro,loop`, `proot apt-get
install`, and `proot dnf install` call
ran to completion but the backend
reported timeout anyway. The success
path was effectively unreachable; the
backends could only ever report
"timeout" + the synthetic `-1` exit.

## The fix

### `LaunchedProcess` gains a `waitFor` callback

`app/src/main/java/com/elysium/vanguard/core/runtime/runner/ProcessLauncher.kt`

```kotlin
data class LaunchedProcess(
    val pid: Int,
    val stop: () -> Unit,
    val waitFor: () -> Int = { -1 }  // PHASE 117
)
```

The default returns `-1` so legacy test
fakes that only supplied `pid` + `stop`
keep compiling. The default is also
distinguishable from a real exit-0
(critical for callers that need to know
"process ran" vs "waitFor was never
called" — see the new test below).

### Production wires `Process.waitFor()`

`app/src/main/java/com/elysium/vanguard/core/runtime/runner/AndroidProcessLauncher.kt`

```kotlin
return LaunchedProcess(
    pid = pid,
    stop = { process.destroy() },
    // PHASE 117 — the production `waitFor`
    // delegates to `Process.waitFor()`
    // (Android-compatible; Java 1.0+).
    waitFor = { process.waitFor() }
)
```

`Process.waitFor()` is the Android-safe
replacement for `Process.onExit()` (Java
9+, not in Android's `java.lang.Process`).
The orchestrator's `AndroidProcessLauncher`
(Phase 82) already used this pattern;
Phase 117 extends it to the runner-level
`LaunchedProcess` so the fileaction
backends can also reap helper processes
reliably.

### Backends use the real `waitFor`

Both `waitForExit` helpers now delegate
directly:

```kotlin
// ProcessLauncherDiskImageBackend
private fun waitForExit(launched: LaunchedProcess): Int =
    launched.waitFor()

// ProcessLauncherPackageInstaller
private fun waitForExit(launched: LaunchedProcess): Int =
    launched.waitFor()
```

The 60-second `Thread.sleep(100)` loops
are gone. No timeout budget is needed:
`Process.waitFor()` blocks until the
child exits, which for `qemu-img convert`
is typically a few seconds, and the
`Dispatchers.IO` context the backends
run on is built for blocking I/O.

The `timeoutMs` parameter is kept
(suppressed as unused) for API stability
— the QEMU boot path still passes
`timeoutMs = 5_000` to document its
intent ("we expect the daemonized QEMU
process to return within 5s"), even
though the value is no longer used as a
budget.

## Tests

### `LaunchedProcessWaitForTest` (new, 6 tests)

`app/src/test/java/com/elysium/vanguard/core/runtime/runner/LaunchedProcessWaitForTest.kt`

Verifies the new `waitFor` contract:

- default `waitFor` returns `-1` (legacy
  fakes still work)
- custom `waitFor` returns the configured
  exit code
- `waitFor` is called once per invocation
  (no internal caching — `Process.waitFor()`
  semantics)
- `stop` and `waitFor` are independent
  callbacks
- exit code 0 / 1 / 2 / 127 / 130 / 137 /
  255 is preserved verbatim
- default `waitFor` (`-1`) is
  distinguishable from a real exit-0

The last point is the regression guard
for the 60-second polling bug: if a
caller forgot to wire `waitFor` (or a
legacy fake was used in production by
accident), the `-1` they get back is
not confusable with success. The polling
loop conflated the two.

### `ProcessLauncherDiskImageBackendTest` (+4 tests)

`app/src/test/java/com/elysium/vanguard/core/fileactions/production/ProcessLauncherDiskImageBackendTest.kt`

The fake `RecordingProcessLauncher` now
accepts a `waitForExitCode` parameter
(defaults to `0` = success). New tests
verify:

- `mount` invokes `waitFor` exactly once
  and treats the return value as the
  exit code
- a non-zero `waitFor` exit code
  propagates as a typed
  `DiskImageResult.Failure` with the code
  in the message
- QCOW2 mount invokes `waitFor` once per
  helper process (`qemu-img convert` +
  `mount`)
- QCOW2 mount short-circuits when
  `qemu-img convert` fails (no second
  process started; only one `waitFor`
  call)

## Files touched

### Production
- `app/src/main/java/com/elysium/vanguard/core/runtime/runner/ProcessLauncher.kt`
  — added `waitFor` parameter to
  `LaunchedProcess` (default `-1`)
- `app/src/main/java/com/elysium/vanguard/core/runtime/runner/AndroidProcessLauncher.kt`
  — wired `waitFor = { process.waitFor() }`
- `app/src/main/java/com/elysium/vanguard/core/fileactions/production/ProcessLauncherDiskImageBackend.kt`
  — replaced 60s polling with
  `launched.waitFor()`
- `app/src/main/java/com/elysium/vanguard/core/fileactions/production/ProcessLauncherPackageInstaller.kt`
  — same

### Test fakes
- `app/src/test/java/com/elysium/vanguard/core/fileactions/production/ProcessLauncherFakes.kt`
  — `RecordingProcessLauncher` now
  accepts `waitForExitCode` + records
  every `waitFor` call
- `app/src/test/java/com/elysium/vanguard/core/runtime/proot/ProotBackendRealTest.kt`
  — trailing-lambda → named `stop =`
  (the new `waitFor` param made the
  trailing-lambda syntax ambiguous)
- `app/src/test/java/com/elysium/vanguard/core/runtime/proot/ProotBackendRealWriteCaptureTest.kt`
  — same
- `app/src/test/java/com/elysium/vanguard/core/runtime/agent/RealAgentCollaboratorsTest.kt`
  — same
- `app/src/androidTest/java/com/elysium/vanguard/core/runtime/critical_e2e/CriticalE2EInstrumentedTest.kt`
  — same

### New tests
- `app/src/test/java/com/elysium/vanguard/core/runtime/runner/LaunchedProcessWaitForTest.kt`
  — 6 tests for the new contract
- `ProcessLauncherDiskImageBackendTest` —
  4 new tests for the integration of
  `waitFor` into the backend

## Compatibility

The `LaunchedProcess` change is
**backward-compatible**: the new
`waitFor` parameter has a default, so
every existing constructor call site
keeps compiling. Test fakes that
relied on the trailing-lambda syntax
(`LaunchedProcess(pid = 1) { ... }`)
were updated to named arguments
(`LaunchedProcess(pid = 1, stop = { ... })`)
to disambiguate which parameter the
trailing lambda binds to (with the new
parameter list, the trailing lambda
would otherwise bind to `waitFor`, not
`stop`).

## What's next

Phase 118 candidates:
- Apply the same real `waitFor` to the
  runner-level `stop()` so the
  `LinuxProotSessionRunner` can read the
  real exit code on session stop (right
  now it sets `exitCode = 0` if the
  session was `Running`, `-1` otherwise
  — the same bug the backends just got
  rid of).
- The `bootVm` daemonize path still
  uses the (now unused) `timeoutMs`
  parameter to express "we expect the
  daemon to fork within 5s" — we could
  surface this as a real `Process.exitValue()`
  poll with a short budget on the
  daemonic path.
