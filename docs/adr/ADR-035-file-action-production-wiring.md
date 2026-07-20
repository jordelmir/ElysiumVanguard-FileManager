# ADR-035 — File Action Production Wiring (Hilt + ViewModel)

| Field        | Value                                                                |
|--------------|----------------------------------------------------------------------|
| Status       | Accepted                                                             |
| Date         | 2026-07-20                                                           |
| Phase        | 94                                                                   |
| Supersedes   | (none)                                                               |
| Superseded by | (none)                                                               |

## Context

Phase 93 (commit `b0b2a7d`) shipped the **resolver + handler abstractions**
for the five File Manager vision gaps:

- `.deb` / `.rpm` / `.pkg.tar.zst` install handlers
- AppImage / `.exe` / `.msi` run handlers
- `.iso` / `.img` / `.qcow2` mount + boot handlers
- Git clone handler
- SMB / WebDAV share mount
- USB OTG device inspect

All five were **tested at the resolver + handler boundary** (the resolver
is a pure function; the handlers take a thin `Runner` interface). The
handlers were JVM-tested with fakes.

What was **missing** was the production wiring:

1. The three `Runner` interfaces (`PackageInstaller`, `GitCloneRunner`,
   `DiskImageBackend`) had **no production implementations** — only
   fakes in tests.
2. The `FileActionViewModel` (which the File Manager's long-press sheet
   will call) had **no concrete class** — only the abstract handler
   boundary.
3. There was **no Hilt module** to provide the production impls to
   the VM's constructor.
4. There was **no narrow environment interface** for the VM to read
   the list of installed distros + VM specs from.

The consequence: the File Manager's long-press menu was
**structurally present** (the sheet renders, the resolver returns
the right actions) but **electrically inert** — there was no code
path from "user taps Install" to "apt-get runs in Debian".

## Decision

### 1. Production `Runner` implementations

Three new classes in `core/fileactions/production/`:

- **`ProcessLauncherPackageInstaller`** — wraps the existing
  `ProcessLauncher`. Algorithm: (1) resolve the target distro via
  `DistroManager.findInstalled`; (2) **physically copy the package
  file into the distro's `/tmp`** (PRoot cannot see host files
  outside the rootfs); (3) spawn `proot --link2symlink -r <rootfs>
  -b /dev -b /proc -b /sys <in-distro-command>` with the package
  manager command (`apt-get install -y`, `dnf install -y`,
  `pacman -U --noconfirm`); (4) wait for the process to exit +
  return the result.
- **`ProcessLauncherGitCloneRunner`** — wraps the existing
  `ProcessLauncher`. Spawns `git clone <url> <destination>` with
  `GIT_TERMINAL_PROMPT=0` to avoid interactive prompts.
- **`ProcessLauncherDiskImageBackend`** — wraps the existing
  `ProcessLauncher`. For ISO / IMG: `mount -o ro,loop` directly.
  For QCOW2: first `qemu-img convert -O raw`, then mount. For
  VM boot: `qemu-system-x86_64 -m 2048 -hda <image> -nographic
  -daemonize` (or `-aarch64` if the preferred VM id contains
  "arm").

All three use a **60-second polling loop** as a `waitFor()` proxy.
The production `ProcessLauncher` does not expose `waitFor()` (the
Android `java.lang.Process` API is incomplete; `Process.pid()` +
`Process.onExit()` are Java 9+ but absent on Android — see the
`engineering-gotchas.md` note for Phase 82). The polling loop is
the Phase 94 stand-in; Phase 95+ adds a real `waitFor` based on
the launcher's `LaunchedProcess` lifecycle.

### 2. Narrow `FileActionEnvironment` interface

`FileActionViewModel` needs three values from the runtime:

- The list of installed Linux distros (id, name, package manager)
- The list of known Windows VM specs (id, name, running state)
- The state of a given VM

The runtime exposes this data through concrete `DistroManager` and
`WindowsVmManager` classes. **Both are `final` classes with heavy
constructors** (5+ parameters each, abstract methods, multiple
backends). The VM cannot reasonably depend on the full managers.

The decision: define a **narrow read-only interface** in the
`fileactions` package:

```kotlin
interface FileActionEnvironment {
    fun installedDistros(): List<DistroInstallation>
    fun windowsVmSpecs(): List<WindowsVmSpec>
    fun windowsVmState(vmId: String): WindowsVmState
}
```

Production wires the interface via `FileActionModule.provideFileActionEnvironment`
to a `DefaultFileActionEnvironment` that delegates to the live
`DistroManager` + `WindowsVmManager`. Tests pass a 5-line
`FakeEnvironment` with fixed data.

**Why not just inject the managers?** The managers' constructors
require `baseDir`, `downloader`, `launcherResolver`, `storageProvider`,
`distroResolver`, `prootTemplate`, etc. Wiring them in a JVM test
requires either (a) building a full app graph (5+ lines of fake
managers) or (b) using reflection / mockito. A narrow interface
captures exactly what the VM reads, and doubles as **documentation**
of the VM's runtime contract.

### 3. `FileActionViewModel` with `@HiltViewModel` + primary constructor

```kotlin
@HiltViewModel
class FileActionViewModel @Inject constructor(
    private val env: FileActionEnvironment,
    private val installPackageHandler: InstallPackageHandler,
    private val gitCloneHandler: GitCloneHandler,
    private val diskImageBackend: DiskImageBackend,
) : ViewModel()
```

- The VM is **`@HiltViewModel`** + **primary constructor with
  `@Inject`**. This is the standard pattern (Phase 79 used a
  custom `Factory` because the constructor needed `MutableStateFlow`
  + `TimestampSource` for an animation cycle; Phase 94's VM has no
  such exotic dependencies).
- The 3 handlers got **`@javax.inject.Inject` constructors** (they
  were already constructor-injected with the runner; now they
  expose themselves for Hilt).
- The VM exposes a single `StateFlow<FileActionUiState>` with
  `sheetVisible`, `targetFile`, `actions`, `lastOutcome`.
- The `execute(action)` method runs the action in `viewModelScope`
  + updates `state.lastOutcome` with `FileActionOutcome.Success` /
  `FileActionOutcome.Failure`.

### 4. `FileActionModule` Hilt module

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object FileActionModule {
    @Provides @Singleton fun providePackageInstaller(...): PackageInstaller
    @Provides @Singleton fun provideGitCloneRunner(...): GitCloneRunner
    @Provides @Singleton fun provideDiskImageBackend(...): DiskImageBackend
    @Provides @Singleton fun provideFileActionEnvironment(...): FileActionEnvironment
}
```

Singleton scope — the runners are stateless wrappers around the
launcher; no reason to create a new instance per VM.

### 5. `DistroManager.listInstalled()` + `WindowsVmManager.listSpecs()`

Two new methods on the existing managers (5 lines each):

- `DistroManager.listInstalled(): List<DistroInstallation>` —
  exposes the cached installed list; refreshes if the cache is
  empty.
- `WindowsVmManager.listSpecs(): List<WindowsVmSpec>` — exposes
  the catalog's full spec list.

These were needed because the existing `DistroManager` only
exposed `findInstalled(id)` (singular, lookup) and the existing
`WindowsVmManager` only exposed `listSpecIds()` (strings, no
display names).

## Consequences

### Positive

- **End-to-end real path**: user long-presses a `.deb` file →
  resolver returns `InstallDebPackage` → sheet renders → user
  taps → `installPackageHandler.install(action)` →
  `installer.installApt(distroId, packageFile)` →
  `ProcessLauncherPackageInstaller.runPackageManager` → `proot
  apt-get install` runs in the distro. **No mocks in this path.**
- **JVM-testable**: the VM takes a 3-method environment
  interface; the fakes are 5 lines each. 7 new VM tests + 3
  GitClone runner tests + 6 DiskImage backend tests = 16 new
  tests, all green.
- **Hilt-managed**: the VM is `@HiltViewModel`; the
  `FileManagerScreen` can call `hiltViewModel()` to get it.

### Negative

- **Polling `waitFor` is a 60s stand-in**. Real waitFor lands in
  Phase 95+. The 60s window is enough for `apt-get install` of
  small packages + `git clone` of medium repos; large installs
  (`apt-get install chromium`) will time out. Phase 95+ will
  add a real `LaunchedProcess.exitCode()` based on the launcher's
  internal coroutine.
- **The `RunAppImage` / `RunWindowsBinary` / `MountNetworkShare` /
  `InspectUsbOtgDevice` actions are still TODO** in the VM's
  `dispatchAction`. The resolver returns them; the VM emits a
  "(queued)" success message. Phase 95+ will wire them to real
  impls.
- **`FileActionViewModel` is not yet wired to `FileManagerScreen`**.
  Phase 95 adds the long-press handler that calls
  `viewModel.openActionSheet(file)`. The VM exists; the UI hook
  does not.

## Alternatives considered

- **Use the managers directly (no `FileActionEnvironment`)**:
  rejected — the managers are `final` and have 5+ param
  constructors. The narrow interface is the right abstraction.
- **`@HiltViewModel` with a custom `Factory`** (Phase 79
  pattern): rejected — Phase 94's VM has no exotic
  constructor deps. `@HiltViewModel` is the simpler path.
- **Make the runners `abstract class` with a default impl**:
  rejected — the runner interfaces are stateless, and an
  `abstract class` adds zero value over an `interface`.

## Related

- **Phase 93 (commit `b0b2a7d`)**: `FileActionResolver` +
  handlers (the abstractions Phase 94 wires up).
- **Phase 79**: `DesktopShellViewModelFactory` — the alternative
  Hilt pattern when the constructor needs exotic args.
- **Phase 82**: `AndroidProcessLauncher.syntheticPidForHandle` —
  why `LaunchedProcess.pid` is a synthetic int (not a real OS
  PID). The Phase 94 polling loop is the workaround.

## File map (new files this phase)

| File | Purpose |
|------|---------|
| `core/fileactions/FileActionEnvironment.kt` | Narrow read-only interface |
| `core/fileactions/FileActionModule.kt` | Hilt module + `DefaultFileActionEnvironment` |
| `core/fileactions/production/ProcessLauncherPackageInstaller.kt` | Production apt/dnf/pacman |
| `core/fileactions/production/ProcessLauncherGitCloneRunner.kt` | Production git clone |
| `core/fileactions/production/ProcessLauncherDiskImageBackend.kt` | Production mount + qemu-img + qemu-system |
| `features/fileactions/FileActionViewModel.kt` | `@HiltViewModel` with `@Inject` |

## File map (modified files)

- `core/fileactions/handlers/InstallPackageHandler.kt` — added
  `@javax.inject.Inject` to the constructor
- `core/fileactions/handlers/GitCloneHandler.kt` — same
- `core/fileactions/handlers/DiskImageHandler.kt` — same
- `core/runtime/distros/DistroManager.kt` — added `listInstalled()`
- `core/runtime/windows/WindowsVmManager.kt` — added `listSpecs()`

## Test count

3468/3468 tests green (16 new this phase: 3 GitClone runner +
6 DiskImage backend + 7 ViewModel).
