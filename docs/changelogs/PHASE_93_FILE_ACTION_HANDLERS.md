# Phase 93 — File Action Resolver + Handlers (the File Manager becomes a control plane)

Phase 93 closes 5 gaps from the master vision (Section 1) in a single phase: AppImage / `.deb` / `.rpm` / `.pkg.tar.zst` / `.exe` / `.msi` handlers, Git clone, SMB / WebDAV, USB OTG, and disk images. The File Manager's "long-press a file" affordance is now backed by a single source of truth (the `FileActionResolver`) that returns the typed action list for any file.

## What shipped

### 1. The sealed `FileAction` class

A new `core/fileactions/FileAction.kt` defines a sealed class with one variant per contextual action. Each variant carries the typed fields the handler needs:

- `InstallDebPackage` / `InstallRpmPackage` / `InstallPacmanPackage` (Linux package install)
- `RunAppImage` / `RunWindowsBinary` (run a portable executable)
- `MountDiskImage` / `BootVmFromImage` (disk image operations)
- `GitClone` (clone a repo from a `.git` descriptor)
- `MountNetworkShare` (SMB / WebDAV / SFTP)
- `InspectUsbOtgDevice` (USB OTG descriptor)

The class is sealed so the consumer pattern-matches on the variant. A free-form string is never the action type.

### 2. The `FileActionContext` snapshot

A new `core/fileactions/FileActionContext.kt` defines the snapshot the resolver reads. The context is a passive value: same file + same context → same action list. The Hilt-injected `FileActionViewModel` builds the context from the live `DistroManager` + `WindowsVmManager` + GitOps + SMB / WebDAV credentials.

### 3. The `FileActionResolver` (pure function)

A new `core/fileactions/FileActionResolver.kt` is the single `object` that maps a file + context to a list of `FileAction`s. The algorithm:

1. Read the file's extension (lowercase, with compound-extension support for `.pkg.tar.zst`).
2. For descriptor files (`.git`, `.smb`, `.webdav`, `.usbotg`), return a single action; the handler reads the body.
3. For installer / runnable / image files, look up the matching package manager / preferred distro / preferred VM in the context and build the concrete action.
4. Return the list (most recommended first).

The resolver is **pure**: no I/O, no coroutines, no Android dependencies. JVM-testable in milliseconds.

### 4. The handlers

Three handlers ship in `core/fileactions/handlers/`:

- **`InstallPackageHandler`** — wraps the existing distro manager + `ProcessLauncher`. The handler takes a `PackageInstaller` interface; production uses the real one; tests use a fake.
- **`GitCloneHandler`** — reads the URL from the first non-blank, non-comment line of the descriptor; validates the URL scheme; launches `git clone` via the `GitCloneRunner` interface.
- **`DiskImageHandler`** — branches on the format. ISO / IMG are mounted read-only via the `LoopManager`; QCOW2 is converted to raw first, then mounted, OR passed directly to QEMU for VM boot. The handler takes a `DiskImageBackend` interface.

### 5. The `FileActionSheet` UI

A new `features/fileactions/FileActionSheet.kt` renders the action list. The sheet is a bottom-sheet (Material 3) with a header (file name + close button) + a `LazyColumn` of `FileActionRow`s (icon + label + description per action). The icons are derived from the action's kind via a small `iconFor(action)` helper.

### 6. Test coverage

- 25 tests in `FileActionResolverTest` (one per `(extension, context)` combination: `.deb` × APT/DNF/PACMAN, `.rpm` × DNF, `.pkg.tar.zst` × PACMAN, `.AppImage`, `.exe`/`.msi`, `.iso`/`.img`/`.qcow2`, `.git`, `.smb`/`.webdav`, `.usbotg`, unknown extensions, case-insensitive matching, the `DiskImageFormat.fromExtension` + `NetworkProtocol.fromUrl` helpers).
- 6 tests in `GitCloneHandlerTest` (URL read from descriptor, comment-skip, invalid URL rejection, `https` + `git@` schemes, missing destination dir is created).

Test count: 3452/3452 (0 broken).

## What we are NOT doing (yet)

- **Wire the `FileActionSheet` into the File Manager's long-press handler** (Phase 94 — one composable change in `FileManagerScreen`).
- **The real `PackageInstaller` / `DiskImageBackend` / `GitCloneRunner` impls** (Phase 94 — wrap the existing `ProcessLauncher` + `LoopManager` + `QemuWindowsVmBackend`).
- **The USB OTG permission flow** (Phase 94+ — `READ_EXTERNAL_STORAGE` + `MANAGE_EXTERNAL_STORAGE`).
- **Run `.sh` / `.py` / `.js` / `.jar` / ELF binaries with the appropriate runtime** (Phase 95+ — the resolver branch + the `RuntimeSelector` wiring).

## Files added

- `app/src/main/java/com/elysium/vanguard/core/fileactions/FileAction.kt`
- `app/src/main/java/com/elysium/vanguard/core/fileactions/FileActionContext.kt`
- `app/src/main/java/com/elysium/vanguard/core/fileactions/FileActionResolver.kt`
- `app/src/main/java/com/elysium/vanguard/core/fileactions/handlers/InstallPackageHandler.kt`
- `app/src/main/java/com/elysium/vanguard/core/fileactions/handlers/GitCloneHandler.kt`
- `app/src/main/java/com/elysium/vanguard/core/fileactions/handlers/DiskImageHandler.kt`
- `app/src/main/java/com/elysium/vanguard/features/fileactions/FileActionSheet.kt`
- `app/src/test/java/com/elysium/vanguard/core/fileactions/FileActionResolverTest.kt`
- `app/src/test/java/com/elysium/vanguard/core/fileactions/handlers/GitCloneHandlerTest.kt`
- `docs/adr/ADR-034-file-action-handlers.md`
- `docs/changelogs/PHASE_93_FILE_ACTION_HANDLERS.md` (this file)

## Build status

- `compileDebugKotlin`: ✓
- `compileDebugUnitTestKotlin`: ✓
- `testDebugUnitTest`: 3452/3452 (31 new + 0 broken)
- `assembleDebug`: ✓ (debug APK built; 97 MB)
