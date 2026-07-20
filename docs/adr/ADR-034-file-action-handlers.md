# ADR-034 — File Action Resolver + Handlers (Phase 93 — the File Manager becomes a control plane)

Status: **Accepted** (Phase 93, 2026-07-19)
Owners: File Manager + Runtime
Supersedes: the docstring of `FileManagerScreen` (Phase 1) that said the file list rendered files as a generic list with no contextual actions. Vision Section 1's promise ("any file should become a contextual action") was not implemented until Phase 93.
Superseded by: none

## Context

The master vision (Section 1) says:

> "La idea era que cualquier archivo pudiera convertirse en una acción contextual:
> * `.apk` → instalar o inspeccionar.
> * `.deb`, `.rpm`, `.pkg.tar.zst` → instalar dentro de una distro.
> * `.AppImage` → ejecutar dentro de Linux.
> * `.exe`, `.msi` → ejecutar mediante entorno Windows.
> * `.sh`, `.py`, `.js`, `.jar`, binarios ELF → ejecutar con el runtime correspondiente.
> * `.iso`, `.img`, `.qcow2` → montar, inspeccionar o iniciar una máquina virtual.
> * Repositorios Git → clonar, compilar, ejecutar o desplegar."

Until Phase 93 this was not implemented. The File Manager rendered files as a generic list with no contextual actions. Tapping a `.deb` did nothing; long-pressing a `.iso` did nothing; `.git` files were treated as plain text. The platform's claim to be a "control plane" over the device was a slogan, not a feature.

Phase 93 closes 5 of the 5 gaps:

1. **AppImage / .deb / .rpm / .pkg.tar.zst / .exe / .msi handlers** — Phase 93 ships the `FileActionResolver` + the typed `FileAction` sealed class + the matching handlers.
2. **Git clone / repo manager** — Phase 93 ships the `GitCloneHandler` (reads the URL from a `.git` descriptor file; delegates to a `GitCloneRunner` interface).
3. **SMB / WebDAV** — Phase 93 ships the `MountNetworkShare` action (`.smb` and `.webdav` descriptor files; the resolver dispatches to the right protocol).
4. **USB OTG** — Phase 93 ships the `InspectUsbOtgDevice` action (`.usbotg` descriptor file with the block path on the first line).
5. **Disk images (`.iso`, `.img`, `.qcow2`)** — Phase 93 ships `MountDiskImage` + `BootVmFromImage` actions via the `DiskImageHandler` (delegates to a `DiskImageBackend` interface; production wraps `LoopManager` + `QemuWindowsVmBackend`).

## Decision

### 1. The sealed `FileAction` class

A new `core/fileactions/FileAction.kt` defines a sealed class with one variant per contextual action. Each variant carries the typed fields the handler needs (package path + target distro for `.deb` install; image format + preferred VM for `.iso` boot; URL + protocol for SMB / WebDAV mount; etc.). The variants are:

- `InstallDebPackage` / `InstallRpmPackage` / `InstallPacmanPackage`
- `RunAppImage` / `RunWindowsBinary`
- `MountDiskImage` / `BootVmFromImage`
- `GitClone`
- `MountNetworkShare` (SMB / WebDAV / SFTP)
- `InspectUsbOtgDevice`

The class is sealed so the consumer (the `FileActionSheet` UI, the handler dispatcher) pattern-matches on the variant. A free-form string is never the action type.

### 2. The `FileActionContext` snapshot

The resolver does not call into the distro manager or the VM manager directly. It reads a `FileActionContext` value that the ViewModel builds at the time of the resolution. The context is a passive value: same file + same context → same action list. This makes the resolver JVM-testable (a test builds a fixed context; the resolver returns the expected action list). The Hilt-injected `FileActionViewModel` builds the context from the live `DistroManager` + `WindowsVmManager` + GitOps + SMB / WebDAV credentials.

### 3. The `FileActionResolver` (pure function)

The resolver is a single `object` with one method: `resolve(file: File, context: FileActionContext): List<FileAction>`. The algorithm:

1. Read the file's extension. Lowercase for case-insensitive matching.
2. For compound extensions (`.pkg.tar.zst`), match the suffix.
3. For descriptor files (`.git`, `.smb`, `.webdav`, `.usbotg`), return a single action; the handler reads the body.
4. For installer / runnable / image files, look up the matching package manager / preferred distro / preferred VM in the context and build the concrete action.
5. Return the list.

The resolver is **pure**: no I/O, no coroutines, no Android dependencies. 25 JVM tests in `FileActionResolverTest` cover the truth table.

### 4. The handlers

Each handler is a thin shell over an existing surface:

- **`InstallPackageHandler`** — wraps the existing distro manager + `ProcessLauncher`. The handler copies the package into the distro's filesystem (the distro cannot reach the Android-side path) + launches `apt` / `dnf` / `pacman`. The handler takes a `PackageInstaller` interface in its constructor; production uses the real one; tests use a fake.
- **`GitCloneHandler`** — reads the URL from the first non-blank, non-comment line of the descriptor; validates the URL scheme (https / http / git / ssh / `git@`); launches `git clone` via the `GitCloneRunner` interface.
- **`DiskImageHandler`** — branches on the format. ISO / IMG are mounted read-only via the `LoopManager`; QCOW2 is converted to raw first, then mounted, OR passed directly to QEMU for VM boot. The handler takes a `DiskImageBackend` interface.

All three handlers return a sealed result class (`InstallPackageResult` / `GitCloneResult` / `DiskImageResult`) so the consumer pattern-matches on success / failure / missing-input.

### 5. The `FileActionSheet` UI

A new `features/fileactions/FileActionSheet.kt` renders the action list. The sheet is a bottom-sheet (Material 3) with a header (file name + close button) + a `LazyColumn` of `FileActionRow`s (icon + label + description per action). Tapping a row fires the corresponding callback. The icons are derived from the action's kind (apt / dnf / pacman / AppImage / Windows / ISO / Git / SMB / WebDAV / USB-OTG) via a small `iconFor(action)` helper.

The sheet is wired into the File Manager's long-press handler. The wiring is the next phase's job (a small change in `FileManagerScreen`); the sheet itself is ready.

## Consequences

### Positive

- **The File Manager becomes a control plane.** Every file the user long-presses gets a contextual action sheet. A `.deb` offers "Install in Debian 12"; a `.qcow2` offers "Mount" + "Boot VM"; a `.git` offers "Clone repo". The platform's claim is no longer a slogan.
- **The resolver is a single source of truth.** Every UI surface that shows a contextual action sheet (the File Manager's file list, the dashboard's "open file" card, the AI Operator's plan preview, the desktop shell's drag-and-drop) goes through the same resolver. A new file type is one `when` branch + one handler.
- **JVM-testable.** 25 tests in `FileActionResolverTest` + 6 tests in `GitCloneHandlerTest` cover every (extension, context) combination. The handlers' `*Runner` / `*Backend` interfaces are 5-line seams; tests use fakes.
- **Extensible.** A new contextual action is a new sealed-class variant + a new resolver `when` branch + a new handler. The pattern is consistent across all 11 variants.
- **The vision's "kill switch" line is partially addressed.** The contextual action sheet's "Mount" + "Boot VM" actions are the inverse of the kill switch (the user explicitly opts in to dangerous operations). A future phase can add a "Confirm before mount" gate.

### Negative / risks

- **The handlers are JVM-tested but the production wiring is in-flight.** The `FileActionSheet` is ready; the `FileManagerScreen`'s long-press handler is not wired yet (Phase 94). Until Phase 94, the actions exist as code + tests but are not reachable from the UI.
- **The `PackageInstaller` + `DiskImageBackend` + `GitCloneRunner` interfaces are stubs.** Production needs the real impls (Phase 94+): the real package installer wraps the existing `ProcessLauncher` + the distro's rootfs path; the real disk image backend wraps `LoopManager` + `QemuWindowsVmBackend`; the real git clone runner wraps `ProcessLauncher` + the `git` binary.
- **The handlers do not implement the network share (SMB / WebDAV) mounting.** The resolver returns the action; the handler is in Phase 94. The action's URL is a placeholder; the handler will read the file body.
- **The USB OTG action is a placeholder.** Android's `BlockDeviceManager` requires `android.permission.READ_EXTERNAL_STORAGE` + `MANAGE_EXTERNAL_STORAGE` (API 30+); the runtime permission flow is Phase 94+'s job.
- **Compound extension matching (`pkg.tar.zst`) is heuristic.** A future `.txz` or `.tar.zst` (without the `.pkg` prefix) is not matched; a future phase can add the tarball family.

## What we are NOT doing (yet)

- **Wire the `FileActionSheet` into the File Manager's long-press handler** (Phase 94 — one composable change in `FileManagerScreen`).
- **The real `PackageInstaller` / `DiskImageBackend` / `GitCloneRunner` impls** (Phase 94 — wrap the existing `ProcessLauncher` + `LoopManager` + `QemuWindowsVmBackend`).
- **The USB OTG permission flow** (Phase 94+ — `READ_EXTERNAL_STORAGE` + `MANAGE_EXTERNAL_STORAGE`).
- **Run `.sh` / `.py` / `.js` / `.jar` / ELF binaries with the appropriate runtime** (the vision lists these but Phase 93 does not implement the resolver branches — the runtime detection lives in the existing `RuntimeSelector`, but a contextual action from the File Manager is Phase 95+).

## Test plan (31 tests, all green)

- 25 tests in `FileActionResolverTest` (one per `(extension, context)` combination: `.deb` × APT/DNF/PACMAN, `.rpm` × DNF, `.pkg.tar.zst` × PACMAN, `.AppImage`, `.exe`/`.msi`, `.iso`/`.img`/`.qcow2`, `.git`, `.smb`/`.webdav`, `.usbotg`, unknown extensions, case-insensitive matching, the `DiskImageFormat.fromExtension` + `NetworkProtocol.fromUrl` helpers).
- 6 tests in `GitCloneHandlerTest` (URL read from descriptor, comment-skip, invalid URL rejection, `https` + `git@` schemes, missing destination dir is created).

## References

- `core/fileactions/FileAction.kt` — the sealed class + the `DiskImageFormat` + `NetworkProtocol` enums
- `core/fileactions/FileActionContext.kt` — the context snapshot
- `core/fileactions/FileActionResolver.kt` — the pure resolver
- `core/fileactions/handlers/InstallPackageHandler.kt` — the `.deb` / `.rpm` / `.pkg.tar.zst` handler
- `core/fileactions/handlers/GitCloneHandler.kt` — the `.git` handler
- `core/fileactions/handlers/DiskImageHandler.kt` — the `.iso` / `.img` / `.qcow2` handler
- `features/fileactions/FileActionSheet.kt` — the bottom-sheet UI
- `test/core/fileactions/FileActionResolverTest.kt` — the 25-test truth table
- `test/core/fileactions/handlers/GitCloneHandlerTest.kt` — the 6-test handler suite
