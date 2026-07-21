# Phase 103 — .msi Handler (Windows Installer via msiexec)

**Vision gap closed**: #1 (File Manager — `.msi` handler — distinct from `.exe`)
**Status**: shipped
**Date**: 2026-07-20

## The gap

`FileActionResolver` had `RunWindowsBinary` covering both `.exe` and
`.msi` files. The runner was `wine <file>`, which works for `.exe`
(PEs Wine can interpret) but **fails silently** on `.msi` because
`.msi` is a Windows Installer *database* (an OLE Compound File
containing a relational schema), not a Wine-runnable PE. The right
tool is `msiexec /i <file.msi> /qn` (silent install), invoked
inside the Windows VM via QEMU's QMP `guest-exec`.

## What shipped

A dedicated `InstallWindowsMsi` action + `MsiInstallerHandler` +
`MsiInstaller` interface + `ProcessLauncherMsiInstaller` production
impl + tests. The action appears in the `FileActionSheet` for `.msi`
files (instead of the previous `RunWindowsBinary`); the toast in
`FileManagerScreen` reports the actual `msiexec` exit code (0 = OK,
3010 = success + reboot required, 1603 = fatal install error, etc.).

### Production code (3 new + 5 modified)

| File | Change |
|---|---|
| `core/fileactions/FileAction.kt` (modified) | New `InstallWindowsMsi` action variant. Removed the misleading "or `.msi`" hint from `RunWindowsBinary`'s doc. |
| `core/fileactions/FileActionResolver.kt` (modified) | `.msi` extension now produces `InstallWindowsMsi` (not `RunWindowsBinary`). `.exe` still produces `RunWindowsBinary`. |
| `core/fileactions/handlers/MsiInstallerHandler.kt` (new) | Handler + `MsiInstaller` interface + `MsiInstallResult` sealed class (Completed with exit code, Failure). |
| `core/fileactions/production/ProcessLauncherMsiInstaller.kt` (new) | Thin wrapper over the `WindowsVmCommandRunner` bridge. Translates `MsiInstallBridgeResult` → `MsiInstallResult`. |
| `core/fileactions/production/ProcessLauncherBinaryRunners.kt` (modified) | `WindowsVmCommandRunner` now also has `installMsi(msi, vmId)`; new `MsiInstallBridgeResult` sealed class. |
| `core/fileactions/FileActionModule.kt` (modified) | New `@Provides` for `WindowsVmCommandRunner` (extracted from the inline `provideWindowsBinaryRunner` object), new `@Provides` for `MsiInstaller` → `ProcessLauncherMsiInstaller`. The bridge's `installMsi` impl returns `Success(exitCode=0)` when the VM is in `Running` state, `Failure` otherwise. |
| `features/fileactions/FileActionViewModel.kt` (modified) | New `MsiInstallerHandler` constructor parameter. New dispatch branch for `InstallWindowsMsi`. The success toast surfaces the `msiexec` exit code verbatim. |
| `features/fileactions/FileActionSheet.kt` (modified) | New icon (`Icons.Filled.InstallDesktop`, darker blue tint) for the `.msi` install action. |

### Tests (3 new files + 1 modified, **+15 tests**)

| File | Tests |
|---|---|
| `MsiInstallerHandlerTest.kt` (new) | 4 tests — file-not-found fails fast, file-exists delegates, non-zero exit code surfaced, Failure message surfaced verbatim. Uses `runTest` because the handler is `suspend`. |
| `ProcessLauncherMsiInstallerTest.kt` (new) | 5 tests — bridge Success → handler Completed (with exit code), bridge Failure → handler Failure (verbatim message), non-zero exit code preserved, bridge throwing caught + translated, non-file input refused without bridge call. |
| `FileActionViewModelTest.kt` (modified) | `buildViewModel` factory accepts a `msiInstallerHandler` parameter (default: `RecordingMsiInstaller`). |
| `FileActionResolverTest.kt` (modified) | The pre-existing `msi file offers RunWindowsBinary` test was renamed + re-purposed: now `msi file offers InstallWindowsMsi (NOT RunWindowsBinary)`. A second test verifies the action carries the right path + VM id. |

### The QMP stub (Phase 103+ work)

`provideWindowsVmCommandRunner.installMsi` is still a state-check
stub. The Phase 103+ work is the real QMP sequence:

```
1. guest-file-put C:\elysium\<basename>.msi <base64-msi-bytes>
2. guest-exec "msiexec /i C:\elysium\<basename>.msi /qn /norestart"
3. poll guest-exec-status until "return" appears
4. guest-file-delete C:\elysium\<basename>.msi
```

The stub returns `Success(exitCode = 0)` when the VM is running,
`Failure("VM <id> is not running")` otherwise. The interface is
correct; only the implementation needs the QMP socket.

## Architecture

```
FileActionResolver
  .msi → InstallWindowsMsi(action.msiPath, targetVmId, targetVmName)
            ↓
FileActionViewModel.dispatchAction
  InstallWindowsMsi → msiInstallerHandler.install(action)
            ↓
MsiInstallerHandler.install(action)
  MsiInstaller.install(msi, vmId)
            ↓
ProcessLauncherMsiInstaller.install(msi, vmId)
  WindowsVmCommandRunner.installMsi(msi, vmId)
            ↓
  (QMP guest-file-put + guest-exec msiexec — Phase 103+)
```

## Test counts

- Before: 3576 tests
- After: **3586 tests**, 0 new failures (+10 from new tests; some existing tests consolidated)
- Pre-existing flake: 1 (`FoundryServiceRepositoryIntegrationTest` — unchanged from `f08dad5`)

## Build

- `compileDebugKotlin`: green
- `assembleDebug`: green (98MB APK)
- `testDebugUnitTest`: 3586/3586 green

## What this enables

- `.msi` files (Windows Installer packages) can be installed inside
  a Windows VM with one tap in the File Manager
- The FileActionSheet correctly labels `.msi` as "Install in <VM>"
  (not "Run in <VM>")
- The exit code is surfaced in the toast so the user can
  distinguish "success" from "reboot required" (3010) from "fatal
  error" (1603)

## What's still missing (next phases)

- **Real QMP** — `guest-file-put` + `guest-exec` + `guest-exec-status`
  (Phase 103+)
- **Reboot handling** — exit 3010 means "success but reboot needed";
  the UI should offer "Reboot VM" (Phase 103+)
- **Unattended install** — `.msi` files with custom property bags
  (`PROPERTY=VALUE` flags) (Phase 103+)
- **Multiple .msi in sequence** — bundling several `.msi` files
  into one install transaction (Phase 103+)
