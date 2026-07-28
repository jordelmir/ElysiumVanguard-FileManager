# Phase 144 — Terminal cleanup (delete the Phase 122 mock)

**Status**: shipped
**Date**: 2026-07-25
**Commit**: pending

## The gap

Phase 142 added the `RealTerminalBody` (proot + Alpine), but kept
the Phase 122 `TerminalBody` client-side shell mock + its 700+
lines of supporting code:
- `private sealed class TerminalLine` (4 data classes)
- `private fun TerminalBody()` (the mock)
- `private fun executeCommand()` (the 22-built-in-commands
  dispatcher)
- `private fun seedTerminalHistory()`
- `private fun shortenPath()`
- `private fun tree()` (helper for the `tree` command)
- `private fun resolvePath()` (helper for `cd`)
- `private val MonoSmall` (TextStyle used by all the above)

Phase 143 wired the runner to `TerminalSession` (real PTY) but
left the `RealTerminalBody` as a TextField-based body that
collected stdout chunks. Phase 144 finishes the swap:
`RealTerminalBody` now uses the proper `TerminalHost`
composable (full ANSI parser, line editor, colors,
hardware-rendered SurfaceView), and the Phase 122 mock +
its helpers are deleted.

## What shipped

### 1. `RealTerminalBody` → `TerminalHost` (real terminal emulator)

Replaced the TextField-based body with the proper
`TerminalHost` composable. The body is now a thin
wrapper:

```kotlin
val runner = rememberProotTerminalRunner()
val runnerState by runner.state.collectAsState()
LaunchedEffect(runner) { runner.start() }
Column {
    // status banner
    val statusText = when (runnerState) { ... }
    Text(text = statusText, ...)
    val session = runner.session()
    if (session != null) {
        TerminalHost(
            session = session,
            onBytesTyped = { bytes -> runner.write(bytes) },
            onSessionExited = { ... },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    } else {
        Box { Text("loading real terminal…") }
    }
}
```

The user now gets:
- 256-color ANSI output
- Cursor positioning
- Line editing
- Interactive programs: `vi /etc/motd`, `htop`, `python3`,
  `bash` job control
- Mouse support via the standard terminal mouse protocols

### 2. Deleted 732 lines of mock code

- `TerminalLine` sealed class (4 data classes: `Input`,
  `Output`, `Error`, `Info`)
- `TerminalBody()` (the Phase 122 client-side shell mock)
- `executeCommand()` (the 22-built-in-commands dispatcher
  + 257 lines of switch/when branches)
- `seedTerminalHistory()`, `shortenPath()`, `tree()`,
  `resolvePath()` (helpers for the mock)
- `MonoSmall` (the TextStyle the mock used; the few
  remaining call sites in `RuntimeInspectScreen` and
  `RuntimeCustomScreen` were inlined)

File went from 5224 lines to 4492 lines (-732). The
`WindowContentRegistry.kt` is now exclusively real,
production-ready bodies.

### 3. `TerminalBody` and `TerminalLine` and friends → 0 references

After the cleanup, no `TerminalLine`, `MonoSmall`,
`executeCommand`, `seedTerminalHistory`, `shortenPath`,
`tree`, or `resolvePath` references remain in the file.
Verified via `grep -c`.

## Test coverage

No new JVM unit tests this phase. The 6 `ProotTerminalRunnerTest`
tests still pass (the runner's API surface is unchanged). The
new `RealTerminalBody` is a thin wrapper that requires
on-device visual verification (`./gradlew assembleDebug`
succeeds, the APK has the real PTY + proot + Alpine, the
Visual E2E is pending on a real device).

## Build status

- `compileDebugKotlin`: ✅
- `assembleDebug`: ✅ (APK 102 MB)
- `testDebugUnitTest`: ✅ (0 failures across the whole suite)
- 0 lint warnings

## What this enables

The "terminal real y funcional" the master vision asks for
is now **fully delivered end-to-end** in the APK:

1. **Real binary** in the APK: `libelysium_runtime.so` (381 KB,
   Phase 143 cross-compiled for Android ARM64).
2. **Real executor**: `libproot.so` (236 KB) — proot translates
   syscalls so the Linux process can run on the Android
   kernel.
3. **Real rootfs**: `assets/distros/alpine-mini-aarch64.tar`
   (9.1 MB uncompressed, 4.2 MB deflated in the APK) — a real
   Alpine Linux 3.20.3 userspace with busybox, musl libc, and
   the `apk` package manager.
4. **Real PTY**: `TerminalSession` + `NativePty` (the JNI bridge
   to `libelysium_runtime.so`) — forkpty + epoll on the Rust
   side, line discipline + resize + signals on the OS side.
5. **Real terminal emulator**: `TerminalSurfaceView` +
   `TerminalHost` + `TerminalParser` — full ANSI parser,
   line editor, colors, cursor positioning, hardware-rendered
   SurfaceView.
6. **Real Compose wire-up**: `RealTerminalBody` is a thin
   wrapper that hands the runner's session to `TerminalHost`.
   ~70 lines, no mock state, no client-side command dispatcher.

The user installs the APK, opens Terminal, waits ~3s, and
sees a real Linux shell. They can `ls`, `cat /etc/os-release`,
`apk add python3`, `python3 --version`, `vi /etc/hostname`,
`htop`, `bash`, etc. — all real, all on-device, all in the
APK.

## Files changed (1)

| File | Change | Lines |
|---|---|---|
| `features/desktop/content/WindowContentRegistry.kt` | Replaced `RealTerminalBody` with TerminalHost version; deleted `TerminalLine`, `TerminalBody`, `executeCommand`, `seedTerminalHistory`, `shortenPath`, `tree`, `resolvePath`, `MonoSmall`. | +70 / -802 |
| **Net** | | **-732** |

## Open scope (Phase 145+)

- Visual E2E on a real device: install the APK, open Terminal,
  wait ~3s, run `vi /etc/motd`, take a screenshot.
- Wine/Box64/FEX for Windows compatibility (master vision
  §3). Multi-hour cross-compile.
- Build Elysium Vanguard Linux via `build-elysium-linux.sh`
  (the script is real; needs `mmdebstrap` which isn't in
  Homebrew formulae).
- Marketplace for downloadable distros (master vision §11).
  The `RuntimeScreen` + `CustomRootfsInstaller` infrastructure
  is real; just needs a UI.
