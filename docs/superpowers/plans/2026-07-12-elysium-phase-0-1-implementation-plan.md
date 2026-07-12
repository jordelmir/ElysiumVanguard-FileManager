# Elysium Vanguard — Phase 0/1 implementation plan

**Status:** active  
**Branch:** `main`  
**Governing design:** `docs/superpowers/specs/2026-07-12-elysium-universal-computing-fabric-design.md`

## Outcome

Deliver the first verified production slice of Elysium Vanguard:

```text
Compose TerminalView
→ Runtime SessionManager
→ Native PTY
→ PRoot Debian
→ incremental VT parser
→ mutable screen buffer
→ damage-tracked renderer
```

The slice is complete only when it runs interactively on the physical Android
device, survives resize/rotation, resolves package repositories and leaves no
child process or file descriptor behind after stop.

## Increment 1 — Synchronize the existing baseline

Scope:

- classify the current dirty tree into runtime, terminal and adaptive UI work;
- verify no secrets or generated build output are staged;
- run the relevant JVM suite and debug build;
- create independent local commits for each coherent group;
- keep remote push out of scope until explicitly requested.

Acceptance:

- `git status --short` contains no unexplained source changes;
- `./gradlew testDebugUnitTest assembleDebug` succeeds;
- all bundled PRoot binaries and notices are intentionally versioned;
- each commit can be reviewed independently.

Rollback:

- revert only the affected atomic commit; do not reset the worktree.

## Increment 2 — Phase 0 stabilization and architecture records

Scope:

- add ADR-001 for runtime backend abstraction;
- add ADR-002 for PTY/parser/renderer architecture;
- record the baseline audit and prioritized risks;
- add the initial threat model and third-party inventory;
- fix the blocking lint error without introducing a lint baseline;
- install/pin the Android NDK and repair the Rust toolchain;
- add deterministic native build entry points.

Acceptance:

- `./gradlew lintDebug testDebugUnitTest assembleDebug` succeeds;
- `cargo fmt --check`, `cargo clippy -- -D warnings` and `cargo test` succeed for
  the native crate;
- Gradle can produce `libelysium_runtime.so` for `arm64-v8a`;
- ADRs define migration and rollback explicitly.

Rollback:

- native backend remains capability-gated; the existing pipe backend is a
  clearly labelled fallback until physical acceptance passes.

## Increment 3 — Runtime domain and native PTY lifecycle

Scope:

- introduce typed runtime/session identifiers, specs, events and errors;
- implement the validated session state machine;
- implement PTY allocation, controlling terminal, process group and `execve`;
- attach PRoot argv/environment without shell interpolation;
- implement non-blocking read/write, resize, wait and ordered shutdown;
- own sessions in a foreground service independent of Activity lifecycle.

Acceptance:

- native unit/integration harness launches `/system/bin/sh` and bundled PRoot;
- `stty size` matches the UI grid and changes after resize;
- Ctrl-C reaches the foreground process group;
- stop produces a typed `ExitReport` and zero surviving process-group members;
- start/stop races and double close are covered by tests.

Rollback:

- disable native capability and return an actionable error; do not silently
  claim PTY support.

## Increment 4 — Terminal parser, buffer and renderer

Scope:

- parse raw UTF-8 bytes incrementally;
- implement required CSI, OSC, SGR, alternate screen, scroll regions, Unicode,
  wide/combining characters, paste, title and mouse modes;
- replace immutable dimensions with a reflow-capable screen model;
- track dirty rows/regions and render in frame batches;
- unify IME, physical keyboard, mouse and touch input;
- preserve pure-black backgrounds and configurable neon ANSI palette.

Acceptance:

- every ANSI fixture produces the same state for every possible fragmentation;
- malformed UTF-8 and escape fuzz inputs do not crash or grow memory without
  bounds;
- `printf`, `vim`, `htop`, `tmux`, `less`, `nano` and `top` render correctly;
- output stress reaches the measured throughput target without ANR;
- rotation and fold/unfold retain the session and coherent cursor geometry.

Rollback:

- retain the last known-good renderer behind the same session interface while
  never routing a real PTY through the old text semantics.

## Increment 5 — Dynamic DNS and runtime diagnostics

Scope:

- observe active network and `LinkProperties`;
- atomically generate and validate distro `resolv.conf`;
- react to Wi-Fi, cellular, VPN and private DNS changes;
- add typed diagnostic evidence and repair actions;
- default local servers to loopback unless the user publishes a port.

Acceptance:

- `getent hosts deb.debian.org` and `apt update` succeed;
- changing active Android network regenerates DNS without restarting the app;
- failure produces `DNS_UNREACHABLE` with evidence and recovery action;
- no service listens on `0.0.0.0` without an explicit policy.

Rollback:

- restore the previous verified `resolv.conf` atomically and report the failed
  network transition.

## Increment 6 — Physical acceptance and handoff to display

Scope:

- install the APK through ADB and launch with `am start -W`;
- execute the mandatory terminal command matrix;
- verify foreground lifecycle, rotation, fold/unfold and process cleanup;
- capture logs, metrics and screenshots as evidence;
- remove or mark the VNC placeholder unavailable;
- open ADR-005 and the implementation slice for embedded X11/Openbox only
  after every prior gate passes.

Acceptance:

```sh
uname -a
cat /etc/os-release
printf '\033[31mROJO\033[0m\n'
stty size
vim
htop
tmux
apt update
```

Additionally:

- `adb shell pidof com.elysium.vanguard` confirms the app remains alive;
- no new `FATAL EXCEPTION`, native crash or ANR appears in logcat;
- stop leaves zero PRoot/session child processes, sockets and temporary files;
- the resulting debug APK is archived with its SHA-256 and exact commit.

## Constraints

- Device validation is blocked whenever ADB is disconnected, but local and
  native verification continues until hardware returns.
- Windows, Wine, Box64, AVF and full desktop work remain outside this plan;
  they begin only after the Phase 0/1 and runtime gates pass.
- No root, hidden Android APIs, downloaded executable code or Windows image is
  assumed.

