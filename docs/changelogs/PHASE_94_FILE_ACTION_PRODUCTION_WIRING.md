# Phase 94 — File Action Production Wiring (Hilt + ViewModel)

| Field        | Value                                                                |
|--------------|----------------------------------------------------------------------|
| Phase        | 94                                                                   |
| Date         | 2026-07-20                                                           |
| Commit       | (this commit)                                                        |
| Depends on   | Phase 93 (FileActionResolver + handlers)                             |
| ADR          | [ADR-035](../adr/ADR-035-file-action-production-wiring.md)           |

## What this phase does

Phase 93 shipped the **resolver + handler abstractions** for the
five File Manager vision gaps. Phase 93 was structural — the
resolver returned the right action, the handler validated the
input, the sheet rendered the row. **No production code path
existed** between "user taps Install" and "apt-get runs in
Debian".

Phase 94 closes that gap:

- **3 production `Runner` impls** that wrap the existing
  `ProcessLauncher`:
  - `ProcessLauncherPackageInstaller` — proot + apt/dnf/pacman
  - `ProcessLauncherGitCloneRunner` — git clone
  - `ProcessLauncherDiskImageBackend` — mount + qemu-img +
    qemu-system
- **Hilt `FileActionModule`** that wires the runners + the
  environment into the Singleton graph.
- **`FileActionViewModel`** (`@HiltViewModel`) that holds the
  FileAction UI state + dispatches actions.
- **Narrow `FileActionEnvironment` interface** (3 methods) so
  the VM depends on values, not on the full `DistroManager` /
  `WindowsVmManager` classes (which are `final` and have 5+
  parameter constructors).
- **`@javax.inject.Inject` on the 3 handler constructors**.
- **`DistroManager.listInstalled()` + `WindowsVmManager.listSpecs()`**
  — the 2 new methods the environment needs.
- **16 new tests** (3 GitClone runner + 6 DiskImage backend + 7
  ViewModel).
- **3468 / 3468 tests green**.

## Files added

| File | Purpose |
|------|---------|
| `core/fileactions/FileActionEnvironment.kt` | Narrow read-only interface (3 methods) |
| `core/fileactions/FileActionModule.kt` | Hilt module + `DefaultFileActionEnvironment` |
| `core/fileactions/production/ProcessLauncherPackageInstaller.kt` | Production apt/dnf/pacman via proot |
| `core/fileactions/production/ProcessLauncherGitCloneRunner.kt` | Production git clone |
| `core/fileactions/production/ProcessLauncherDiskImageBackend.kt` | Production mount + qemu-img + qemu |
| `features/fileactions/FileActionViewModel.kt` | `@HiltViewModel` with `@Inject` |

## Files modified

| File | Change |
|------|--------|
| `core/fileactions/handlers/InstallPackageHandler.kt` | Added `@javax.inject.Inject` to constructor |
| `core/fileactions/handlers/GitCloneHandler.kt` | Same |
| `core/fileactions/handlers/DiskImageHandler.kt` | Same |
| `core/runtime/distros/DistroManager.kt` | Added `listInstalled()` (5 lines) |
| `core/runtime/windows/WindowsVmManager.kt` | Added `listSpecs()` (1 line) |

## Algorithm — `ProcessLauncherPackageInstaller`

For each `.deb` / `.rpm` / `.pkg.tar.zst` file the user installs:

1. **Resolve the target distro** via `DistroManager.findInstalled(id)`.
   Returns `MissingDistro` if not installed.
2. **Copy the package file** from its Android-side path
   (e.g. `/sdcard/Download/`) into `<rootfs>/tmp/<name>`. The
   PRoot process cannot see files outside the rootfs.
3. **Spawn PRoot** with the package manager command:
   ```
   proot --link2symlink -r <rootfs>
         -b /dev -b /proc -b /sys
         /usr/bin/env <in-distro-command>
   ```
   - `apt-get install -y /tmp/<name>.deb`
   - `dnf install -y /tmp/<name>.rpm`
   - `pacman -U --noconfirm /tmp/<name>.pkg.tar.zst`
4. **Wait for the process to exit** (60s polling loop; Phase 95+
   swaps in a real `waitFor`).
5. **Return** `Success(distroId, packageName, exitCode)` or
   `Failure(message)`.

The `DEBIAN_FRONTEND=noninteractive` env var is set so apt does
not prompt for dialogs.

## Algorithm — `ProcessLauncherDiskImageBackend`

For each `.iso` / `.img` / `.qcow2` file the user mounts or boots:

**Mount (read-only)**:
- **ISO / IMG**: `mount -o ro,loop <image> <mountpoint>` directly.
- **QCOW2**: first `qemu-img convert -O raw -f qcow2 <image>
  <raw>` to convert to raw, then `mount -o ro,loop <raw>
  <mountpoint>`. The conversion is cached in
  `<filesDir>/fileaction-scratch/raw/`.

**Boot (VM)**:
- **QCOW2**: spawn `qemu-system-x86_64 -m 2048 -hda <image>
  -nographic -daemonize` directly.
- **ISO / IMG**: first `qemu-img convert -O qcow2 <image>
  <qcow>` to convert to QCOW2, then spawn QEMU. The
  conversion is cached in `<filesDir>/fileaction-scratch/boot/`.
- The preferred VM id (if set) selects the architecture:
  `qemu-system-aarch64` for ids containing "arm",
  `qemu-system-x86_64` otherwise.

The mount point is `<filesDir>/fileaction-scratch/mnt/<image-name>/`.
The user can browse the contents as a folder via the File
Manager.

## Algorithm — `ProcessLauncherGitCloneRunner`

1. **Spawn `git clone`** via `ProcessLauncher` with the URL +
   destination directory.
2. **Set `GIT_TERMINAL_PROMPT=0`** to avoid interactive prompts
   (no TTY on Android).
3. **Wait for the process to exit** (60s polling loop).
4. **Return** `Success(url, destination, exitCode)` or
   `Failure(message)`.

The runner does not read the descriptor file (the
`GitCloneHandler` does that — it validates the URL is a valid
Git URL before passing it to the runner).

## Test count

3468/3468 green (16 new):
- 3 `ProcessLauncherGitCloneRunnerTest`
- 6 `ProcessLauncherDiskImageBackendTest`
- 7 `FileActionViewModelTest`

## What this does NOT do (deferred to Phase 95+)

- **Wire `FileActionSheet` into `FileManagerScreen`'s long-press
  handler**. The VM + sheet exist; the UI hook does not. Phase 95
  adds the long-press handler that calls `viewModel.openActionSheet(file)`.
- **Fix the "al tocar inspect se cierra" crash**. Most likely in
  `RuntimeInspectViewModel`'s coroutine init. Phase 95+ once the
  device reconnects.
- **Real `waitFor()` on `LaunchedProcess`**. The 60s polling loop
  is the Phase 94 stand-in.
- **`RunAppImage` / `RunWindowsBinary` / `MountNetworkShare` /
  `InspectUsbOtgDevice` actions**. The VM emits a "(queued)"
  success message for these. Phase 95+ will wire them to real
  impls (FUSE mount for AppImage, QEMU/Wine for .exe, smbclient
  for SMB, gvfs for USB OTG).

## Build verification

- `./gradlew testDebugUnitTest` — 3468/3468 green
- `./gradlew assembleDebug` — APK built
- 0 lint errors, 0 warnings
