# Phase 141 — ProotTerminalRunner (the real terminal runner)

**Status**: shipped
**Date**: 2026-07-25
**Commit**: pending

## The gap

Phase 140 shipped a real bundled rootfs (Alpine minirootfs
3.20.3 for aarch64) and a hash-verified extractor. The
extractor's output directory is a real Linux userspace,
but **nothing in the platform was actually using it** —
the desktop's Terminal body (Phase 122) was a client-side
shell mock with 22 built-in commands. The user types
`apt install curl` and gets a fake response. That was the
gap.

The vision says "real terminal, real Linux". The path to
real is:

1. Bundled real rootfs in the APK (Phase 140 ✅)
2. Proot binary in the APK (`libproot.so`, 236 KB)
3. **A runner that spawns proot and pipes the I/O**
   (Phase 141 ✅)
4. A Composable body that uses the runner
   (Phase 142 — the wire-up of the desktop body)

Phase 141 is the runner. Phase 142 will replace the
Phase 122 mock Terminal body.

## What shipped

`ProotTerminalRunner` — a self-contained coroutine-friendly
component that:

- Takes a `ProotLocation` (where `libproot.so` lives) +
  a `BundledDistro` (Alpine) + a `BundledRootfsExtractor`
  (Phase 140) + a `prootLibraryDir` (where proot's
  `libtalloc2.so` + `libandroid-shmem.so` +
  `libproot_loader.so` live).
- On `start()`:
  1. Calls `extractor.ensureExtracted(...)` to get the
     real rootfs directory (or fail with a typed
     `BundledRootfsError`).
  2. Builds the proot command via
     `NativeProotLauncher.buildShellCommand(rootfsDir, "")`
     (the empty script = interactive login shell).
  3. Adds the env vars proot needs:
     `LD_LIBRARY_PATH` (so proot finds its shared libs),
     `PROOT_LOADER` (so proot can invoke the loader),
     `PROOT_NO_SECCOMP=1` (Android 8-16 vendor kernels
     segfault under seccomp), `PROOT_DONT_POLLUTE_ROOTFS=1`.
  4. `ProcessBuilder.start()` the proot process.
  5. Pump `stdout` + `stderr` on dedicated IO
     coroutines; emit each chunk as a `String` via the
     `_output` SharedFlow.
  6. Wait for the OS exit code on a third coroutine;
     emit `State.Exited(rc)`.
- Exposes:
  - `state: StateFlow<State>` — `NotStarted` / `Starting`
    / `Running(pid)` / `Exited(rc)` / `Error(message)`.
  - `output: SharedFlow<String>` — stdout chunks.
  - `events: SharedFlow<Event>` — `Exited(rc)`,
    `Failed(message)`, `Stderr(chunk)`.
- `write(bytes: ByteArray)` — sends user input to the
  shell's stdin. Safe from any thread (schedules the
  write on the IO dispatcher).
- `sendInterrupt()` — convenience for `Ctrl+C`.
- `stop()` — graceful (close stdin, then `waitFor(2s)`,
  then `destroyForcibly` + `waitFor(500ms)`). Idempotent.
- `dispose()` — cancels the runner's coroutine scope.
  The caller MUST call this on screen exit or the pump
  coroutines outlive the Composable.

### Why a custom runner instead of `LinuxProotSessionRunner`?

The session runner is workspace-centric: it owns a
`Workspace` + `WorkspaceSession.LinuxProot` and
participates in the snapshot/rollback story. The
terminal is a different consumer — a foreground,
ephemeral, interactively-driven process. We don't need
the snapshot/rollback metadata for a typed REPL; we
need a tight read/write loop with lifecycle events the
UI can render.

### Production Hilt wiring

`ProotTerminalRunnerModule` provides:

- `BundledRootfsSourceFactory` — a typed wrapper around
  `Application.assets` so the runner can build
  `AndroidAssetRootfsSource` instances lazily. We
  needed a class wrapper because Hilt's
  `Function1<BundledDistro, BundledRootfsSource>` type
  resolution produced a "similar bindings" warning in
  AGP 8.
- `ProotLocation` — `ProotNativeLibrary.default()` with
  the platform's `nativeLibraryDir` (where the APK
  extracts `libproot.so` at install time). Throws if
  the binary is missing (defense-in-depth).
- `ProotTerminalRunner` — `@Singleton`. Wires the
  proot location + Alpine distro + extractor + library
  dir. Registers the source factory with the
  `AssetManagerSourceProvider` so the runner can
  resolve the bundled asset on first `start()`.

`ProotTerminalRunnerEntryPoint` + `rememberProotTerminalRunner()`
Composable helper — Hilt EntryPoint bridge for
Composable-only contexts.

### Source-factory pattern

The runner does not know about `Application.assets` —
that's an Android `Context`-dependent API the JVM test
runner cannot construct. Instead, the runner has a
small lazy `AssetManagerSourceProvider` that the
production Hilt module populates with a factory on
construction. Tests register a `FileBundledRootfsSource`
factory in `@Before` so the JVM test environment
doesn't need a `Context`.

## Test coverage (6 JVM unit tests, all green)

`ProotTerminalRunnerTest`:

1. **`start reports Error when source provider is not registered`** —
   without a registered source, `start()` fails cleanly
   and reports `State.Error`. This is the contract the
   test suite relies on: the runner never crashes on a
   missing dependency; it surfaces a typed error.
2. **`start is idempotent — second call is a no-op`** —
   two `start()` calls in a row produce the same state
   (no double-spawn).
3. **`stop is idempotent — second call is a no-op`** —
   same for `stop()`.
4. **`dispose cancels the coroutine scope`** — after
   `dispose()`, `stop()` is safe and the state remains
   `NotStarted`.
5. **`runner accepts a custom BundledDistro for future distros`** —
   the runner is parametrized by distro. Today the only
   entry is alpine-mini, but the contract supports any
   `BundledDistro` (e.g. a future Debian slim entry).
6. **`state flow transitions to Starting on first start`** —
   `start()` first publishes `State.Starting` (or
   `State.Error` if the source factory is missing).

## Bug fixes (1)

- **Hilt doesn't like `Function1<...>` wildcards.** The
  first wiring used
  `sourceFactory: (BundledDistro) -> BundledRootfsSource`
  as a Hilt `@Provides` parameter, but the Dagger
  generator produced a "similar bindings" error in AGP 8
  because the wildcard resolution for the function type
  doesn't match cleanly. Fix: wrap the factory in a
  `BundledRootfsSourceFactory` class. Hilt is happy
  with concrete classes; the lambda lives inside the
  class's `create(distro: BundledDistro)` method.

## Build status

- `compileDebugKotlin`: ✅
- `assembleDebug`: ✅ (APK 102 MB; +5 MB for the bundled
  Alpine rootfs; AAPT2 deflates the 9.1 MB tar to 4.2 MB
  on disk)
- `testDebugUnitTest --tests "...distros.terminal.*"`: ✅
  (6/6 green)
- All distros tests still pass (no regression)
- Lint warnings: 0 new

## What this enables

The runner is the runtime; the UI body (Phase 142) is
the only thing standing between the user and a real
`ash` shell running inside the bundled Alpine. Phase 142
is straightforward:

- Inject `ProotTerminalRunner` into the Terminal body.
- On first composition, call `runner.start()`; collect
  `runner.output` into a `mutableStateListOf<Line>`.
- Wire the existing `TextField` `onValueChange` to
  `runner.write(bytes)`.
- Replace the 22-built-in-commands `executeCommand(...)`
  function with the runner's stdout feed.

The Phase 122 mock disappears in Phase 142.

## Files added (3)

| File | Lines | Purpose |
|---|---|---|
| `core/runtime/distros/terminal/ProotTerminalRunner.kt` | 295 | The runner (State/Event/StateFlow API) |
| `core/runtime/distros/terminal/ProotTerminalRunnerModule.kt` | 130 | Hilt wiring + EntryPoint bridge |
| `test/.../terminal/ProotTerminalRunnerTest.kt` | 154 | 6 JVM unit tests |
| **Total Kotlin** | | **579 lines** (production) + 154 (test) |
