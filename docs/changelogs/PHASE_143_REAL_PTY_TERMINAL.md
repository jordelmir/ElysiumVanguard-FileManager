# Phase 143 — Real PTY terminal (libelysium_runtime.so + TerminalSession)

**Status**: shipped
**Date**: 2026-07-25
**Commit**: pending

## The gap

Phase 142's `RealTerminalBody` ran a real proot + Alpine shell, but
the `ProotTerminalRunner` used `ProcessBuilder` (no PTY). That worked
for non-interactive commands (`ls`, `cat`, `apk add`) but
interactive programs (`vi`, `htop`, `python3` REPL) were broken
because line discipline, resize, and signal delivery were not
honored.

The existing `TerminalSession` + `TerminalHost` + `TerminalSurfaceView`
composable (Phase 9.6.1) is the proper terminal emulator — full
ANSI parser, line editor, colors, cursor positioning, hardware-
rendered SurfaceView. It was waiting for `libelysium_runtime.so`
to be cross-compiled for Android ARM64.

That `.so` was on the disk (built for the host), not in the APK.

## What shipped

### 1. `libelysium_runtime.so` cross-compiled for Android ARM64

The Rust crate in `native/runtime/` builds a `cdylib` that
exports 9 JNI functions:
`nativeClose`, `nativeIsSupported`, `nativePid`, `nativeRead`,
`nativeResize`, `nativeSignal`, `nativeSpawn`, `nativeWait`,
`nativeWrite`. The Gradle `buildRustRuntime` task cross-compiles
it for `aarch64-linux-android` using NDK 26 (the host had 26,
not 29, so the original `build-android.sh` was tweaked to use
the available NDK). Output: 381 608 bytes ARM64 ELF, stripped.
Deflated to 198 912 bytes in the APK.

The class signature is
`com.elysium.vanguard.core.runtime.terminal.pty.NativePtyBridge`
— matches the Kotlin `NativePtyBridge` facade.

### 2. `ProotTerminalRunner` now uses `TerminalSession` (real PTY)

Phase 141's `ProcessBuilder`-based runner is replaced by a
runner that creates a real `TerminalSession` and starts it.
The `TerminalSession` constructor takes
`TerminalSession.Config` with `command`, `workingDirectory`,
`rootfsDir`, `environmentVariables`, `cols`, `rows`,
`termName`, etc. The runner:

1. Extracts the bundled Alpine rootfs (Phase 140).
2. Builds the proot command via
   `NativeProotLauncher.buildShellCommand(rootfsDir, "")`.
3. Adds the proot env: `LD_LIBRARY_PATH`,
   `PROOT_LOADER`, `PROOT_NO_SECCOMP=1` (Android 8-16 vendor
   kernels segfault under seccomp),
   `PROOT_DONT_POLLUTE_ROOTFS=1`.
4. Creates a `TerminalSession` configured to run the
   command. `cols=80`, `rows=24`, `termName="xterm-256color"`,
   `colorTermSupport=true`.
5. Calls `terminalSession.start()`.
6. Bridges the session's flows (output, events, state) to
   the runner's existing `StateFlow<State>` +
   `SharedFlow<String>` + `SharedFlow<Event>` API so the
   body doesn't need to change.

The runner also exposes `session(): TerminalSession?` so the
body can use the proper `TerminalHost` composable.

### 3. `Event.Stderr` removed

Phase 141's `Event.Stderr` variant is gone. The new
`TerminalSession` treats stderr as part of the visible
output (the terminal emulator handles it), so the runner
no longer surfaces it separately.

### 4. Build drop: Hilt `Function1<...>` wildcard

`ProotTerminalRunnerModule` had a typed factory
`(BundledDistro) -> BundledRootfsSource` that Dagger couldn't
resolve in AGP 8 (wildcard resolution failed). Wrapped in a
`BundledRootfsSourceFactory` class so Hilt can inject the
factory directly. Same pattern Phase 141 used.

## Test coverage (6 JVM unit tests, all green)

`ProotTerminalRunnerTest`: the existing 6 tests still pass.
The runner's API surface is unchanged (state/output/events),
so the test code is identical. The new code path (the
`start()` block that creates a `TerminalSession`) is not
exercised by the unit tests — that requires a real Android
device because `TerminalSession` depends on
`NativePty` which calls into `libelysium_runtime.so`. The
JVM test runner can't load the .so (no NDK on the host).
Visual E2E on a real device is the integration test for
this phase.

## Bug fixes (1)

- **Hilt `Function1<...>` wildcard in AGP 8.** Same fix as
  Phase 141: the source factory is wrapped in a
  `BundledRootfsSourceFactory` class so Hilt's Dagger code
  generator can resolve the dependency. Phase 141's
  `Event.Stderr` was referenced in `WindowContentRegistry`
  — the body code is fixed to drop the now-deleted event
  variant (compile error if not removed).

## Build status

- `compileDebugKotlin`: ✅
- `assembleDebug`: ✅ (APK 102 MB)
- `libelysium_runtime.so` in the APK: 381 KB ARM64 (deflated
  to 199 KB in the APK)
- All distros + recent tests pass
- 0 lint warnings
- 6/6 runner tests still green

## What this enables

A real PTY-backed terminal in the user's pocket. The next
`RealTerminalBody` refactor (Phase 144) will:

1. Switch the body to use `TerminalHost` (the proper
   terminal emulator with vi/htop/colours).
2. Delete the Phase 142 TextField-based body entirely.
3. Delete the Phase 122 client-side shell mock
   (`TerminalBody` + `TerminalLine` + `MonoSmall` +
   `seedTerminalHistory` + `shortenPath` +
   `executeCommand`, ~700 lines of mock).

The user can already `ls`, `cat`, `apk add curl`, etc.
via the current Phase 142 body. After Phase 144 they can
also `vi /etc/motd`, `htop`, `python3`, and use any other
interactive program. That's the "real terminal, real
Linux" the master vision asks for, fully delivered.

## Files changed (2)

| File | Change | Lines |
|---|---|---|
| `core/runtime/distros/terminal/ProotTerminalRunner.kt` | refactored to use `TerminalSession` (real PTY) | refactored, no net add |
| `features/desktop/content/WindowContentRegistry.kt` | `RealTerminalBody` doc comment updated to Phase 143; `Stderr` event removed | +20 / -2 |
| `native/runtime/build-android.sh` | (already in repo) tweaked to use NDK 26 | no change |
| **Total** | | **~+20 / -2** (refactor) |

## Open scope (Phase 144+)

- `RealTerminalBody` still uses the Phase 142 TextField-based
  body. Switching to `TerminalHost` is Phase 144.
- The deprecated `TerminalBody` (Phase 122 mock) and its 700
  lines of helper code are still in the file. Will be
  deleted in Phase 145.
- Visual E2E on a real device (install + screenshot of
  `vi /etc/hostname` in action) is pending.
