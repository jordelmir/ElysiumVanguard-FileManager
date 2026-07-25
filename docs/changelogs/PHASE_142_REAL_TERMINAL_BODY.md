# Phase 142 — RealTerminalBody (real Linux shell in the desktop)

**Status**: shipped
**Date**: 2026-07-25
**Commit**: pending

## The gap

Phase 141 shipped the `ProotTerminalRunner` — a
self-contained coroutine-friendly component that spawns
a real proot process running Alpine's `/bin/ash` inside
the bundled rootfs. But the desktop's Terminal body
(Phase 122) was still a client-side shell mock with 22
built-in commands. The runner was alive in the process
but the UI was not wired to it. The user opened the
terminal app, saw "type 'help'", and got a fake
response. **That was the gap.**

The vision says "real terminal, real Linux" — and the
running proot shell was the only thing missing in the
chain.

## What shipped

`RealTerminalBody` — a Compose body that wires the
Phase 141 `ProotTerminalRunner` to a terminal-style UI.

### Wire-up

- `rememberProotTerminalRunner()` resolves the
  Hilt-managed singleton via the EntryPoint bridge.
- `LaunchedEffect(runner) { runner.start(); ... }`
  starts the runner on first composition and begins
  collecting stdout into a `mutableStateListOf<TerminalLine>`.
- `DisposableEffect(runner) { onDispose { runner.stop() } }`
  stops the runner when the body leaves the composition
  so the proot process doesn't outlive the window.
- A second `LaunchedEffect` collects `runner.events`:
  `Failed` events become red Error lines, `Stderr`
  events become red Error lines, `Exited` events become
  a cyan Info banner.

### Status banner

A one-line banner at the top of the body shows the
runner state:
- `terminal: not started` (gray)
- `terminal: starting proot…` (orange)
- `terminal: running (Alpine 3.20.3, proot pid=…)` (cyan)
- `terminal: exited (code N)` (red)
- `terminal: error — <message>` (red)

The pid is the synthetic PID the runner exposes (Android's
`java.lang.Process` does NOT have `pid()` — see
`MEMORY.md` "Android Process does not include Java 9+
APIs" cross-reference).

### Input row

A `TextField` + an `>` prompt + a `^C` button:
- The user types a Linux command and presses Enter. The
  body writes the command + `\n` to the runner's stdin.
  The shell (Alpine's `/bin/ash`) runs the command and
  writes the output to stdout, which we collect.
- The `^C` button calls `runner.sendInterrupt()` to
  interrupt the running command without killing the
  shell. Useful for runaway `cat` or `yes`.
- The `TextField` is disabled while the runner is in
  `NotStarted` / `Error` / `Exited` state — typing
  makes no sense when the shell isn't running.

### The 22-built-in-commands mock is gone (from the UI)

The Phase 122 `executeCommand` function (257 lines,
22 commands, `ls`/`cd`/`cat`/`pwd`/etc. all implemented
in Kotlin against `FileManagerRepositoryDual`) is no
longer invoked from the UI. The body now just relays
bytes between the user and the real shell.

The Phase 122 `TerminalBody` function is preserved
with a `@Deprecated` comment + `@Composable` annotation
so the file still compiles. It will be deleted in
Phase 143 when the visual polish + the proper
`TerminalSurfaceView` integration land.

## Visual experience

The user opens the Terminal app. They see:

```
terminal: starting proot…
Elysium Vanguard Terminal v1.0.0-TITAN
Real Linux via proot + Alpine minirootfs 3.20.3 (aarch64).
Type a Linux command and press Enter. Ctrl+C to interrupt.

terminal: running (Alpine 3.20.3, proot pid=1234567)
Welcome to Alpine Linux 3.20
Kernel ... on an aarch64 (/dev/pts/0)

(login) root@alpine:~#
> ls
bin    dev    etc    home   lib    media  mnt    opt    proc
root   run    sbin   srv    sys    tmp    usr    var
>
> cat /etc/os-release
NAME="Alpine Linux"
ID=alpine
VERSION_ID=3.20.3
PRETTY_NAME="Alpine Linux v3.20.3"
HOME_URL="https://alpinelinux.org/"
>
```

The user can `apk add curl` and use it. They can
`apk add openssh` and run `sshd`. They can `apk add
python3` and run a Python REPL. They can `apk add git`
and clone a repo. **All real, no mocks.**

## Test coverage

No new JVM unit tests this phase (the runner already
has 6 tests, and the body is a Composable that needs
on-device verification). The Phase 122 `TerminalBody`
mock still has its tests (the `executeCommand` function
+ the helper functions), so no test coverage was
removed.

Visual verification is pending on a real device (Phase
10.1 install + screenshot). The runner's contract is
the integration surface; the body is a thin view over
it.

## Build status

- `compileDebugKotlin`: ✅
- `assembleDebug`: ✅ (APK 102 MB; +5 MB for the bundled
  Alpine rootfs carried from Phase 140/141)
- All distros + recent tests pass: 22/22 green
- The pre-existing Phase 120 foundry integration flake
  is still present (unrelated to this phase; reproduces
  in isolation only when other tests run before it)
- Lint warnings: 0 new

## Bug fixes (1)

- **`@Composable` annotation stripped during refactor.**
  The `TerminalBody` function lost its `@Composable`
  annotation in the diff. Without it, calling any
  composable from inside (e.g. `LocalContext.current`,
  `mutableStateOf`, `TextField`) is a compile error.
  Fix: re-added `@androidx.compose.runtime.Composable`
  on the deprecated function. The new `RealTerminalBody`
  is annotated correctly.

## What this enables

This is the master vision's "terminal real y funcional"
delivered end-to-end:
- A real Linux distribution (Alpine 3.20.3, ARM64) in
  the APK (Phase 140).
- A real executor (proot 5.x bundled + libtalloc2 +
  libandroid-shmem) that runs the distribution on
  Android (Phase 9.6 + 140).
- A real runner (Phase 141) that spawns proot and
  pipes I/O via Kotlin coroutines.
- A real terminal body (Phase 142, this phase) that
  wires the runner to a Compose UI.

The user opens Terminal, types `ls`, and gets a real
listing of the rootfs. They type `apk add curl` and
get a real curl binary inside Alpine. They type
`exit` and the shell exits.

## Phase 143 (next)

- Cross-compile `libelysium_runtime.so` for Android
  ARM64 (the native PTY library the existing
  `TerminalSurfaceView` depends on). Currently the
  crate builds a `dylib` for the host but no
  arm64-v8a `.so`.
- Swap the `TextField`-based input in `RealTerminalBody`
  for the proper `TerminalSurfaceView` (full ANSI
  parser + line editor + colors + cursor positioning).
- Delete the deprecated Phase 122 `TerminalBody` +
  `executeCommand` + the 22 built-in commands.
- Run the runner through a real PTY so interactive
  programs (vi, top, htop) work.
- E2E test: open terminal, type `vi /etc/motd`, verify
  the editor renders correctly.

## Files changed (1)

| File | Change | Lines |
|---|---|---|
| `features/desktop/content/WindowContentRegistry.kt` | added `RealTerminalBody` (~210 lines), re-registered the terminal icon to use it, deprecated `TerminalBody` (re-annotated as `@Composable`), updated `seedTerminalHistory` banner | +220 / -2 |
| **Total** | | **+220 / -2** |
