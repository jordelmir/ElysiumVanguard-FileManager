# Phase 36 — `RuntimeModule` Hilt wiring + `@HiltViewModel` conversion

Date: 2026-07-17
Status: **Shipped** — `assembleDebug` green, 1351 tests, 0 failures, 2 skipped.

## What landed

The runtime's process-scoped collaborators
(`WorkspaceManager`, `WindowsVmManager`,
`RuntimeEventBus`, `ProcessLauncher`,
`LinuxProotSessionRunner`, `WindowsVmSessionRunner`,
`SessionRunner`) are now wired into the app's Hilt
graph. The two new ViewModels
(`MainScreenViewModel` + `WorkspacesViewModel`) are
now `@HiltViewModel` and can be constructed by
`hiltViewModel()` in Compose.

Until Phase 36, every collaborator was JVM-testable
but not injectable; the Compose side could not have
called the new ViewModels because the DI graph
had no bindings. Phase 36 closes that gap.

### Files

**Production (3 new, 2 modified):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/RuntimeModule.kt` — new
  Hilt module. Provides `WorkspaceStore`,
  `WorkspaceManager`, `WindowsVmBackend`,
  `WindowsVmManager`, `RuntimeEventLog`,
  `RuntimeEventBus`, `BusToLogAdapter`,
  `ProcessLauncher`, `LinuxProotSessionRunner`,
  `WindowsVmSessionRunner`, `SessionRunner` (the
  registry), and the two qualifier-bound values
  (`@MainScreenRecentEventsCapacity`,
  `@WallClock`) the ViewModels need.
- `app/src/main/java/com/elysium/vanguard/core/runtime/runner/AndroidProcessLauncher.kt` — new production
  [ProcessLauncher] impl. Wraps [ProcessBuilder];
  reads the child pid via reflection on
  [Process.toHandle] (a Java 9+ API that the
  Android stub `Process` in the JVM unit-test
  classpath does not have). The reflection
  failure mode is a typed `-1`; the
  [LaunchedProcess.stop] callback closes over the
  [Process] reference, so a missing pid does not
  prevent shutdown.
- `app/src/main/java/com/elysium/vanguard/core/runtime/MainScreenRecentEventsCapacity.kt` — the
  `@Qualifier` annotation the module uses to
  bind the `recentEventsCapacity` `Int` so it
  does not collide with any other `Int` Hilt
  might bind. (The companion `@WallClock`
  qualifier lives in `RuntimeModule.kt`.)
- `app/src/main/java/com/elysium/vanguard/core/runtime/ui/MainScreenViewModel.kt` — converted to
  `@HiltViewModel` + `@Inject constructor`. The
  class now extends `ViewModel` and implements
  `AutoCloseable` (so the existing `.use { }`
  tests work unchanged). `onCleared()` delegates
  to `close()`. `recentEventsCapacity` is now
  injected via `@MainScreenRecentEventsCapacity`
  (production value: 20).
- `app/src/main/java/com/elysium/vanguard/core/runtime/ui/WorkspacesViewModel.kt` — same conversion.
  `clock: () -> Long` is now injected via
  `@WallClock` (production value:
  `System::currentTimeMillis`).

**Tests (1 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/runner/AndroidProcessLauncherTest.kt` — 7
  tests pin the launcher:
  - empty command list → `IllegalArgumentException`
  - non-directory cwd → `IOException`
  - missing cwd → `IOException`
  - real command → `LaunchedProcess` with a valid
    pid (or `-1` on the stub JVM) and a non-null
    stop callback
  - `stop()` is idempotent
  - shell metacharacters are passed through
    unchanged (the launcher is NOT a shell)
  - host env is replaced, not inherited

## Why this matters

The Compose UI side of the runtime was the last
big piece of the runtime. Until Phase 36, even
if a future Compose screen tried to use
`hiltViewModel<MainScreenViewModel>()`, the
Hilt processor would fail with "no binding for
WorkspaceManager" / "no binding for
WindowsVmManager" / etc. Phase 36 closes that
gap.

The two ViewModels are also now part of the
official DI graph, which means:

- The Compose side can use `hiltViewModel()`
  without a factory adapter.
- A future instrumented test (in
  `androidTest/`) can verify the ViewModels
  receive the right production collaborators
  end-to-end (Hilt's test rule is `@HiltAndroidTest`).
- The Compose side can navigate to the runtime
  screen with a Hilt-managed ViewModel; the
  ViewModel's collaborators are the production
  ones (the real `LinuxProotSessionRunner` over
  the real `DistroManager`, the real
  `SessionRunnerRegistry` over the real two
  per-kind runners, etc.).

## Design notes

### Why `@HiltViewModel` requires `ViewModel`

Hilt's `@HiltViewModel` annotation is checked at
KSP-time: the annotated class must extend
`androidx.lifecycle.ViewModel`. The pre-Phase-36
`MainScreenViewModel` and `WorkspacesViewModel`
extended `AutoCloseable` (the test-only contract
that allowed `.use { }`). Phase 36 extends both:
`class MainScreenViewModel @Inject constructor(...) : ViewModel(), AutoCloseable`.
The `AutoCloseable` interface is preserved so the
JVM tests do not change — they still call
`vm.use { }` and `vm.close()`. The new
`onCleared()` override delegates to `close()` so
the production and test paths share one
implementation.

### Why a `@Qualifier` for the `Int` capacity

Hilt needs a type + qualifier to bind an `Int`.
Without a qualifier, every `Int` injection site
would be ambiguous. The `@MainScreenRecentEventsCapacity`
qualifier makes the binding explicit: the
ViewModel's `recentEventsCapacity` parameter is
the integer the module provides. Tests inject the
value directly into the constructor (the test
fixture is not Hilt).

### Why reflection for the child pid

`Process.toHandle().pid()` is a Java 9+ API. The
project compiles to JVM 17 (sourceCompatibility),
but the JVM unit-test classpath ships the Android
stub `Process` class — it has no `toHandle()`.
The compile-time reference fails. Reflection on
`Process::class.java.getMethod("toHandle")` is
the smallest workaround: the call succeeds on
API 26+ devices (the project's `minSdk`) and
returns `-1` on the unit-test classpath. The
pid is informational; the `LaunchedProcess.stop`
callback closes over the `Process` reference, so
a missing pid never blocks shutdown.

### Why `WindowsVmManager` is its own runner backend

`WindowsVmManager` implements
`WindowsVmSessionBackend` (Phase 31). The module
binds `WindowsVmSessionBackend` to
`provideWindowsVmSessionBackend(manager)` — a
1-line bridge that lets the
`WindowsVmSessionRunner` see the manager
through the narrow Phase-31 interface instead of
the wide Phase-22 surface. The pattern mirrors
`DistroManager` → `DistroSessionBackend` (Phase
30).

## What the module does

| Binding | Production impl |
|---|---|
| `WorkspaceStore` | `FileWorkspaceStore(context.filesDir/workspaces)` |
| `WorkspaceManager` | `WorkspaceManager(store)` |
| `WindowsVmBackend` | `QemuWindowsVmBackend(context.filesDir/winvms)` |
| `WindowsVmManager` | `WindowsVmManager(baseDir, backend)` |
| `RuntimeEventLog` | `RuntimeEventLog(filesDir/runtime/audit.ndjson)` |
| `RuntimeEventBus` | `SynchronizedEventBus` |
| `BusToLogAdapter` | `BusToLogAdapter(bus, log)` |
| `ProcessLauncher` | `AndroidProcessLauncher` |
| `WindowsVmSessionBackend` | `WindowsVmManager` (the manager implements the interface) |
| `LinuxProotSessionRunner` | runner(distroManager, processLauncher, eventBus) |
| `WindowsVmSessionRunner` | runner(manager, eventBus) |
| `SessionRunner` | `SessionRunnerRegistry(linux, windows)` |
| `@MainScreenRecentEventsCapacity Int` | `20` |
| `@WallClock () -> Long` | `System::currentTimeMillis` |

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `AndroidProcessLauncherTest` | 7 (new) | 0 |
| **Project total** | **1351** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

The follow-up after Phase 36 is the **Compose
UI for the runtime** — a screen that consumes
both `@HiltViewModel`s (`MainScreenViewModel` +
`WorkspacesViewModel`) and renders:
- the status bar (`runningSessionCount`,
  `linuxDistrosInstalled`,
  `windowsVmsRunning`),
- the workspace list (one card per
  `WorkspaceSummary`),
- the per-session Start / Stop buttons that
  call `vm.startSession(workspace, session)` and
  `vm.stopSession(workspace, session)`.

The screen needs an `androidTest/` (Hilt
instrumented) test for end-to-end coverage —
the only piece of the runtime still missing.
