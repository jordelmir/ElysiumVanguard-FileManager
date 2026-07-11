# Elysium Vanguard Runtime and Linux Redesign

**Date:** 2026-07-11
**Status:** Design validated from the delegated product direction
**Scope:** First delivery in the continuous Elysium Vanguard improvement program

## 1. Executive summary

The first delivery replaces the current pipe-backed, shape-tested Runtime with a verifiable terminal and Linux foundation. Elysium will own a real pseudo-terminal, process lifecycle, distro installation state, `proot` launch path, storage bridge, adaptive Runtime workspace, and physical-device acceptance loop.

The delivery must never infer that Linux works because a rootfs contains `/bin/bash`. Runtime capabilities are measured on the Android device and reflected honestly in the UI. A distro is considered usable only after its native launcher, PTY, shell, architecture, and rootfs probes succeed.

This is the first subproject in a larger sequence. It does not reduce the full program: Linux desktop, Elysium Word, Elysium Sheet, file management, music, gallery, and global personalization remain required follow-on deliveries.

## 2. Audit findings

The current implementation contains useful foundations but does not yet prove the requested behavior:

- `TerminalSession` launches `ProcessBuilder` with stdin/stdout pipes. It does not own a PTY.
- `resize()` only resizes the in-memory buffer. It does not issue `TIOCSWINSZ`.
- `sendInterrupt()` writes byte `0x03`; without a controlling terminal, this is not equivalent to delivering `SIGINT` to a foreground process group.
- `DirectExecDistroLauncher` detects rootfs shell files and builds commands, but JVM tests only validate command shape. They do not prove Android can execute a glibc rootfs or run a package manager.
- The generated debug APK contains ML/OCR native libraries but no `proot` executable and no PTY JNI library.
- The current terminal service is declared as a `dataSync` foreground service even though an interactive terminal is not data synchronization.
- Runtime unit tests pass, but there is no instrumented PTY/proot acceptance test and no connected Android device in the current ADB state.
- The worktree already contains uncommitted adaptive-theme work. Runtime implementation must preserve and build on it without sweeping or formatting unrelated files.

## 3. Program decomposition

The continuous program is split into complete, independently verifiable deliveries:

1. **R1 — Native Runtime and terminal:** this design.
2. **R2 — Linux desktop and graphical apps:** display server, local transport, input, clipboard, audio, and lifecycle on top of R1.
3. **O1 — Elysium Word and Sheet:** document fidelity, editing workflows, responsive tool surfaces, import/export, and recovery.
4. **F1 — File manager:** navigation model, operations, dual pane, permissions, previews, reliability, and responsive density.
5. **M1 — Music and gallery:** MediaStore-first libraries, permissions, background playback, albums, edits, and lifecycle.
6. **T1 — Global product system:** color routing, pure-black/uniform-color invariant, accessibility, adaptive layouts, and cross-feature QA.

Each delivery ends with build, automated tests, APK installation, launch, process check, log inspection, and device screenshots when hardware is connected.

## 4. R1 goals

R1 must provide:

- A real PTY-backed Android shell.
- A real PTY-backed `proot` Linux shell for supported ABIs.
- Explicit capability probes and honest degraded states.
- Multiple named sessions owned outside the composable lifecycle.
- Correct input, resize, signals, Unicode, selection, copy/paste, and scrollback.
- Transactional distro download, verification, extraction, activation, repair, and removal.
- A permission-scoped filesystem bridge visible from both Elysium and Linux.
- Adaptive Runtime UI for compact, medium/foldable, expanded, and large windows.
- Pure black where black is intended and uniform palette color where a colored surface is intended.
- A working global color action from the dashboard and Runtime workspace.
- Sovereign and Store distribution boundaries that do not pretend to offer identical native-code capabilities.

## 5. R1 non-goals

R1 will not:

- Emulate a Linux kernel or provide root access.
- Claim that a process survives Android killing the application process. It survives configuration changes and backgrounding while its foreground service remains alive; after process death, the session is reported as interrupted and can be restarted.
- Ship a graphical Linux desktop. R2 owns display transport and graphical apps.
- Download Android native runtime binaries in the Store flavor.
- Auto-run commands from remote manifests or arbitrary URLs.
- Rewrite unrelated Word, Sheet, file, music, or gallery internals during the Runtime delivery.

## 6. Distribution model

### 6.1 Sovereign flavor

The Sovereign APK is the full direct-distribution build for GitHub/F-Droid/private delivery. It includes:

- ABI-specific PTY JNI libraries.
- ABI-specific `proot` executable artifacts packaged with the APK.
- Rootfs catalog and download support.
- Custom rootfs installation behind an explicit risk confirmation.
- Optional broad-storage bridge only after explicit user authorization.

### 6.2 Store flavor

The Store build preserves the Android shell and terminal workspace but disables downloading native executable rootfs payloads. Any native executable required by the build must be delivered through the store artifact or an approved dynamic feature mechanism.

The UI must label unavailable capabilities instead of showing disabled distro actions without explanation.

### 6.3 Build boundary

Gradle introduces a `distribution` flavor dimension with `sovereign` and `store`. Shared terminal, UI, and state-machine code stays in `main`; policy-specific catalogs, manifests, resources, and launch providers live in flavor source sets.

## 7. Architecture

### 7.1 Terminal emulator core

The current parser and cell buffer remain an initial seam, but gain conformance work required by interactive applications:

- Main and alternate screen buffers.
- Configurable bounded scrollback.
- Correct cursor visibility and style.
- Wide characters, combining marks, and grapheme-safe selection.
- Bracketed paste.
- Common CSI, OSC, SGR, erase, insert/delete, and scroll-region behavior.
- Application cursor/keypad modes needed by shells, editors, `less`, and `htop`.
- Dirty-row rendering so output does not repaint the entire terminal.

The emulator accepts bytes and emits immutable display snapshots or dirty regions. It does not launch processes and does not know about Compose.

### 7.2 Process backend boundary

Introduce `TerminalProcessBackend` with operations equivalent to:

- `start(spec): ProcessHandle`
- `read(handle, target): Int`
- `write(handle, bytes): Int`
- `resize(handle, columns, rows, pixelWidth, pixelHeight)`
- `signal(handle, signal)`
- `waitFor(handle): ExitStatus`
- `close(handle)`

Implementations:

- `NativePtyBackend` for Android shell and Linux/proot sessions.
- `LegacyPipeBackend` retained only as an explicit diagnostic fallback for the Android shell. It is never advertised as PTY or Linux support.

### 7.3 Native PTY bridge

Add an NDK library, `libelysium_pty.so`, with a narrow JNI surface. The native side will use Android/Bionic PTY primitives (`posix_openpt`, `grantpt`, `unlockpt`, `ptsname`, `fork`, `setsid`, `dup2`, and `ioctl`) rather than assuming `forkpty` exists on every supported API.

The child process:

1. Creates a session and controlling terminal.
2. Connects stdin, stdout, and stderr to the slave PTY.
3. Applies working directory and environment.
4. Resets signal handlers.
5. Calls `execve` with an argv array, never a concatenated shell command unless the user explicitly starts a shell script.

The parent owns the master descriptor and process group. Resize issues `TIOCSWINSZ`; interrupt sends `SIGINT` to the foreground process group; close sends `SIGHUP`, waits briefly, then escalates to `SIGTERM` and `SIGKILL` when needed.

### 7.4 Session service

`TerminalSessionService` owns all live processes and exposes a local Binder API backed by flows. A ViewModel only selects and observes sessions; it never owns a `Process`.

The service maintains:

- Session ID, name, kind, distro ID, creation time, and lifecycle.
- Process handle, terminal emulator instance, and I/O jobs.
- Active dimensions and input mode.
- Exit status and bounded diagnostic events.
- An app-private persisted session descriptor for restart suggestions after process death.

While sessions are active in the background, the Sovereign build uses a visible `specialUse` foreground service notification with a precise manifest subtype. The service is started while an activity is visible, conforms to current background-start restrictions, and stops when the last session closes unless the user explicitly keeps a session running.

The Store build must pass Play review for any `specialUse` declaration; otherwise it keeps sessions only while the app is visible/bound and communicates that limit.

### 7.5 Linux launcher

`ProotLauncher` replaces Direct-Exec as the only path that may advertise a Linux distro as runnable. It resolves the packaged executable for the device ABI, validates it, and creates argv without shell interpolation.

The launch specification includes:

- Root identity emulation without device root.
- Rootfs path.
- Working directory inside the distro.
- `/dev`, `/proc`, and required Android system binds supported by the tested proot build.
- Explicit Elysium shared mounts.
- Clean `HOME`, `PATH`, `TMPDIR`, `TERM`, locale, and distro-specific shell values.
- Login shell command selected from validated rootfs metadata.

`DirectExecDistroLauncher` remains diagnostic-only and cannot set `canRunElfBinaries=true` without a successful on-device executable probe.

### 7.6 Runtime capability probe

At application start and after runtime installation, `RuntimeCapabilityProbe` records:

- Device ABI and supported APK ABI artifact.
- PTY JNI load and open/resize/signal smoke result.
- Proot artifact presence, executable launch, version, and ptrace/seccomp result.
- Rootfs architecture and required shell/loader presence.
- Storage bridge read/write state.
- Background-session support for the current flavor and OS.

The UI consumes this report. File presence alone is never sufficient evidence.

### 7.7 Distro installation engine

The installer is a state machine:

`Available -> Downloading -> Verifying -> Extracting -> Validating -> Activating -> Ready`

Failure states preserve stage, cause, retryability, and cleanup action. Installation behavior:

- Uses resumable downloads where the origin supports byte ranges.
- Checks free space before download and again before extraction.
- Pins size and SHA-256 in the catalog; official manifests are signed by the Elysium release key.
- Rejects path traversal, unsafe absolute paths, and links that escape the staging root.
- Extracts under an app-private staging directory.
- Validates rootfs architecture, shell, essential directories, and a harmless proot command.
- Activates by atomic rename and preserves the previous healthy version until validation succeeds.
- Makes cancellation cooperative and removes only staging artifacts.
- Supports repair by revalidation and selective reinstall.

### 7.8 Filesystem bridge

The bridge exposes only explicit mount declarations:

- App-private exchange: `/elysium/shared`.
- User-selected SAF trees: `/elysium/trees/<label>`.
- Optional Sovereign broad-storage mount: `/elysium/storage`, with prominent permission disclosure.

Each mount has read-only/read-write mode, source identity, availability, and revocation state. Linux never receives vault keys or private app directories by default.

## 8. Runtime data flow

1. The user selects a distro.
2. `DistroInstallCoordinator` streams installation state to the Runtime workspace.
3. A validated rootfs becomes atomically active.
4. The user creates a session.
5. `TerminalSessionService` asks `RuntimeCapabilityProbe` for a current launch decision.
6. `ProotLauncher` builds a typed launch specification.
7. `NativePtyBackend` starts the child and returns a master descriptor and PID.
8. The service pumps bytes into the emulator and emits dirty display state.
9. The terminal surface renders; keyboard, IME, mouse, and accessibility input return encoded bytes to the PTY.
10. Resize and fold/window changes update both the emulator and native PTY.
11. Exit, error, notification stop, or user close transitions the session through one authoritative lifecycle.

## 9. Adaptive product experience

### 9.1 Runtime workspace

Runtime is organized around sessions and capabilities rather than a flat catalog. It contains:

- Runtime health summary.
- Recent and running sessions.
- Android shell action.
- Installed distros.
- Distro catalog.
- Storage mounts and snapshots entry points.
- Diagnostics and repair actions.

Every distro card has one primary action derived from state: install, resume, verify, repair, open, stop, update, or inspect error.

### 9.2 Terminal workspace

The terminal provides:

- Named session tabs and session switcher.
- PTY/proot/ABI/mount capability chips.
- Search, copy, paste, select all, clear scrollback, font size, and restart.
- Extra-key row for Escape, Control, Alt, Tab, arrows, Home/End, Page Up/Down, and configurable shortcuts.
- Hardware keyboard shortcuts and IME-safe text input.
- Accessible descriptions for actions; terminal content exposes a readable text snapshot where practical.

### 9.3 Window classes and folds

Layouts use current window metrics, not device labels or fixed screen dimensions:

- **Compact:** one destination at a time; terminal receives maximum height.
- **Medium:** list-detail when width permits; otherwise compact navigation with denser controls.
- **Expanded:** persistent session/catalog pane plus terminal.
- **Large/extra-large:** navigation rail, session/catalog pane, terminal, and optional supporting diagnostics pane.
- **Separating fold:** no critical content crosses the hinge; book posture maps list and terminal to separate regions.

Folding/unfolding updates layout and PTY size without creating a new session.

### 9.4 Visual invariants

- Intentional black is exactly `#000000`.
- Colored surfaces use one palette-derived color consistently across their full clipped shape.
- Black rectangles are not layered inside colored cards.
- Borders and glow may vary in alpha, but the surface fill remains uniform.
- Text and controls retain usable contrast at every palette preset.
- The global color action in the upper-right dashboard area and Runtime opens the same customization route.
- A palette update propagates immediately through `LocalGlobalTheme`/the canonical palette provider without recreating active sessions.

## 10. Error handling

Errors are typed and actionable:

- `NetworkUnavailable`, `HttpFailure`, `DownloadInterrupted`.
- `InsufficientSpace`, `StorageRevoked`, `ExtractionFailure`.
- `ChecksumMismatch`, `SignatureInvalid`, `UnsafeArchive`.
- `UnsupportedAbi`, `NativePtyUnavailable`, `ProotUnavailable`, `ProotBlocked`.
- `RootfsInvalid`, `ShellMissing`, `ArchitectureMismatch`.
- `ProcessStartFailure`, `ProcessExited`, `SignalFailure`, `IoFailure`.

UI errors include a short explanation, affected item, safe retry/repair/remove action, and a copyable diagnostic code. Logs contain paths only when necessary and never include document contents, clipboard contents, commands marked sensitive, or vault material.

## 11. Security and licensing

- No root or privilege escalation.
- Official distro metadata is pinned and signed.
- Custom URLs are disabled by default and require explicit trust confirmation.
- Archive extraction is path-safe and resource-bounded.
- Launches use argv/env arrays and canonical paths to prevent command injection.
- Mounts are least-privilege and revocable.
- Native sources and build hashes are reproducible and documented.
- `proot` and any reused terminal components receive a license audit, notices, corresponding-source links, and required source disclosure before distribution.
- Termux bootstrap packages are not copied unchanged: packages compiled for another application prefix are not treated as compatible with Elysium.

## 12. Verification strategy

### 12.1 JVM tests

- Installer state transitions, cancellation, retry, and rollback.
- Manifest parsing, signature/hash decisions, ABI matching, and safe extraction.
- Proot argv and environment generation.
- Session registry and lifecycle state.
- Terminal parser, alternate screen, Unicode width, selection, scrollback, and key encoding.
- Adaptive layout policy as pure decision functions where possible.

### 12.2 Native and instrumented tests

- PTY open/read/write/wait.
- `TIOCSWINSZ` reflected by `stty size`.
- `SIGINT` interrupts the foreground command without killing the session shell.
- UTF-8 round trip and large-output backpressure.
- JNI cleanup after close and repeated session creation.
- Proot version/probe and a minimal rootfs shell on each shipped ABI.

### 12.3 UI tests

- Runtime state cards expose the correct primary action.
- Color action navigates and palette changes propagate.
- Compact, medium, expanded, and large layouts avoid overlap and clipping.
- Fold-aware layout does not place controls under a separating hinge.
- Terminal tabs survive activity recreation while the service remains alive.
- Pure-black and uniform-color invariants are checked with golden screenshots or deterministic pixel probes.

### 12.4 Build and artifact checks

- `testDebugUnitTest` and relevant instrumented tests pass.
- `assembleSovereignDebug` and `assembleStoreDebug` pass.
- APK inventory proves the intended ABI PTY/proot artifacts are present only in allowed variants.
- APK size and native symbols are reported.
- No unrelated dirty files are staged with R1 commits.

### 12.5 Physical Android acceptance

On a connected device, preferably the Honor Magic V2:

1. Install with `adb install -r -d`.
2. Launch with `am start -W`.
3. Confirm resumed activity and live process.
4. Open an Android shell and prove `tty`, `stty size`, input, resize, and `Ctrl+C`.
5. Install the supported Alpine rootfs through the UI.
6. Open Alpine and prove PTY, `/etc/os-release`, package-manager version, working directory, and interactive command behavior.
7. Create a file through `/elysium/shared` and see it in Elysium File Manager; then create one in File Manager and read it from Linux.
8. Fold/unfold or rotate and prove the same session PID remains while dimensions update.
9. Background/foreground with the visible session notification and prove the session remains usable.
10. Inspect `logcat` for new fatal exceptions, ANRs, native crashes, SELinux denials, and launcher errors.
11. Capture compact/folded and expanded/unfolded screenshots.

Network-dependent package installation is an additional smoke test, not the sole proof of the PTY or launcher.

## 13. Rollout and rollback

R1 lands in reviewable slices:

1. Backend interfaces and characterization tests.
2. Native PTY bridge and Android-shell device proof.
3. Session service and terminal lifecycle.
4. Emulator/input correctness and terminal workspace.
5. Sovereign/Store flavor boundary.
6. Packaged proot and capability probes.
7. Transactional Alpine installation and storage bridge.
8. Adaptive Runtime redesign and global color route.
9. Full regression, artifact inspection, ADB installation, and device acceptance.

The legacy pipe backend remains available behind a diagnostic-only switch until Android-shell PTY acceptance passes. It is not used as a silent Linux fallback. Existing distro data is not deleted during migration; old installations are discovered, validated, and either adopted or marked as requiring repair.

## 14. Acceptance criteria

R1 is complete only when all are true:

- The Sovereign APK contains a supported PTY library and packaged proot artifact for the connected device ABI.
- Android shell and Alpine sessions report a real PTY and pass interactive device tests.
- Runtime UI reports actual capabilities and never promotes Direct-Exec file presence as Linux execution proof.
- Session resize, interrupt, close, rotation, fold/unfold, and background/foreground behaviors pass on hardware.
- `/elysium/shared` round-trips files between Linux and File Manager.
- Installation corruption, cancellation, insufficient space, bad hash, unsupported ABI, and native-launch failures produce typed recoverable states.
- Compact, medium/foldable, expanded, and tablet-class layouts have no overlap or clipped primary actions.
- Intended black pixels are pure black and colored fills are uniform.
- The upper-right global color action works and updates Runtime immediately.
- Automated Runtime tests, both flavor builds, APK inventory, ADB install/launch, process checks, and clean crash logs are recorded as evidence.

If no Android is connected, R1 may be compiled and prepared but cannot be declared complete.

## 15. External constraints and references

- Google Play prohibits downloading executable native code outside its update mechanism: <https://support.google.com/googleplay/android-developer/answer/16313518>
- Android foreground-service types and `specialUse` review requirements: <https://developer.android.com/develop/background-work/services/fgs/service-types>
- Android foreground-service background-start restrictions: <https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>
- Android adaptive window size classes: <https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes>
- Android fold-aware guidance: <https://developer.android.com/develop/adaptive-apps/guides/foldables/make-your-app-fold-aware>
- Termux package builds are tied to app package name and prefix: <https://github.com/termux/termux-packages/wiki/Building-packages>
- Termux execution environment documents `/bin`/`usr/bin` and `termux-exec` constraints: <https://github.com/termux/termux-packages/wiki/Termux-execution-environment>

## 16. Known prerequisites

- The current machine has an Android SDK but no installed NDK directory. The implementation plan must provision and pin an NDK version before native work.
- The repository has no active CMake/native build wiring for Runtime yet.
- No Android device is currently listed by `adb devices -l`.
- Product flavors do not yet exist.
- Native artifact provenance, licenses, and reproducible build instructions must land alongside binaries.
