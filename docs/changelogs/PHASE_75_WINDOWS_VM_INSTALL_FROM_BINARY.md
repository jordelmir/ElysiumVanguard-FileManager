# Phase 75 — WindowsVmManager.installFromBinary (the agent's createWindowsEnvironment path)

The agent's `createWindowsEnvironment` was a typed
"not yet wired" failure (Phase 73). The agent's parser +
executor accepted the `CreateWindowsEnvironment` action, but
the production collaborator returned a string telling the user
to use the gateway Command Core. Phase 75 wires the production
path.

## What shipped

### 1. The production install seam

`app/src/main/java/com/elysium/vanguard/core/runtime/windows/WindowsVmManager.installFromBinary(binaryPath, runtimeKind)`

The flow:

  1. **Validate the binary on disk** — the `binaryPath` is
     a host path. The manager returns
     [WindowsVmError.BinaryNotFound] when the file is
     missing or is not a regular file.
  2. **Reject Wine runtimes** — `WINE_BOX64` and `WINE_FEX`
     are Linux-guest runtimes, not Windows VMs. The
     Windows VM manager is the `QEMU_VM` route. Wine runtimes
     return [WindowsVmError.WineRuntimeNotSupported] with a
     message that names the Wine-aware installer seam
     (a future phase adds the Wine path).
  3. **Map `runtimeKind` to a spec** — the manager asks
     [WindowsVmCatalog.findByRuntimeKind] for a spec whose
     `runtimeKind` field matches. The lookup is
     case-insensitive. When the catalog has no match, the
     manager returns
     [WindowsVmError.NoSpecForRuntimeKind].
  4. **Stage the binary to the VM's directory** — the
     manager copies `binaryPath` to
     `<baseDir>/staging/<specId>/<binary.name>`. The user
     installs the binary manually inside the running guest
     (the platform does not yet author an unattended
     install answer file). Staging failures
     (filesystem permission, out of disk, etc.) return
     [WindowsVmError.StagingFailed] with the underlying
     `IOException`'s message.
  5. **Start the VM** — the manager delegates to the
     [WindowsVmBackend.start] (QEMU in production, in-memory
     in tests). The backend transitions the VM to
     `Booting` (typical) or `Running` (fast-boot image).
     The manager's `states` map caches the new state.

### 2. The runtime kind on the spec

`WindowsVmSpec.runtimeKind: String` — every spec now carries
its runtime kind. The official catalog's three specs
(`win10-pro-22h2`, `win11-pro-23h2`, `win-server-2019`)
default to `QEMU_VM`. A future phase adds Wine-aware
specs (with `runtimeKind = "WINE_BOX64"` / `"WINE_FEX"`)
that delegate to a Linux guest + Wine install path.

`WindowsVmCatalog.findByRuntimeKind(runtimeKind: String): WindowsVmSpec?` — the lookup is
case-insensitive and returns the first match (the catalog
is sorted by id).

### 3. The new typed errors

`WindowsVmError` gained four new variants:

  - `BinaryNotFound(binaryPath)` — the binary at the given
    host path is missing or not a regular file.
  - `WineRuntimeNotSupported(runtimeKind)` — the runtime
    kind is a Wine runtime. The Windows VM manager is the
    QEMU_VM route.
  - `NoSpecForRuntimeKind(runtimeKind)` — the catalog has
    no spec with a matching runtime kind.
  - `StagingFailed(binaryPath, reason)` — the filesystem
    refused the copy (permissions, disk full, etc.).

The `RealAgentCollaborators.createWindowsEnvironment` converts
each variant to a `AgentStepResult.Failure` with the error's
human-readable message, so the executor's audit log records
what went wrong.

### 4. The agent's path is now real

`RealAgentCollaborators.createWindowsEnvironment(binaryPath, runtimeKind)` no longer returns a string. It
delegates to `WindowsVmManager.installFromBinary` and returns
a `Success` with the new VM's state ("Windows environment
created: VM is in state 'Booting' with binary 'setup.exe'
staged for install") or a `Failure` with the typed error.

The user can now:
- Type "create windows env for /sdcard/Downloads/setup.exe via
  qemu" into the Local Agent surface and the agent will
  stage the binary + start the VM.
- Or use the COMMAND CORE (HTTP-gateway) for the same
  operation.

## Build / test status

- `compileDebugKotlin` — green.
- `assembleDebug` — green.
- `testDebugUnitTest` — **all 2582 unit tests green, 0 failures**
  (5 new in `WindowsVmManagerTest`, 4 new in
  `RealAgentCollaboratorsTest`).
- 0 new lint warnings.
- Install on the user's device — verified.

## Files

- `app/src/main/java/com/elysium/vanguard/core/runtime/windows/WindowsVmSpec.kt` (UPDATED: `runtimeKind` field)
- `app/src/main/java/com/elysium/vanguard/core/runtime/windows/WindowsVmCatalog.kt` (UPDATED: `findByRuntimeKind`)
- `app/src/main/java/com/elysium/vanguard/core/runtime/windows/WindowsVmManager.kt` (UPDATED: `installFromBinary` + 4 new error variants)
- `app/src/main/java/com/elysium/vanguard/core/runtime/agent/RealAgentCollaborators.kt` (UPDATED: `createWindowsEnvironment` now calls `installFromBinary`)
- `app/src/test/java/com/elysium/vanguard/core/runtime/windows/WindowsVmManagerTest.kt` (UPDATED: 5 new tests for `installFromBinary`)
- `app/src/test/java/com/elysium/vanguard/core/runtime/agent/RealAgentCollaboratorsTest.kt` (UPDATED: 4 new tests for `createWindowsEnvironment`)

## Notes for follow-ups

- **Wine install path** — the master vision names `Wine +
  Box64` and `Wine + FEX` as separate Windows-app
  runtimes. A future phase adds a `WineInstallManager`
  (or extends the existing one) that runs Wine inside
  a Linux proot guest and installs the binary there. The
  typed "WineRuntimeNotSupported" error is the seam
  that future code dispatches to.
- **Unattended install answer files** — the platform
  currently stages the binary and asks the user to
  install manually. A future phase authors an
  unattended install answer file (e.g. an
  `AutoInstall.iss` for Inno Setup) and pipes it to the
  VM via QEMU guest agent. The staged binary is the
  input to the answer-file pipeline.
- **VM image verification** — the `WindowsVmSpec` has a
  placeholder signature (`"0".repeat(192)`). A future
  phase wires the Ed25519 signer + verifier (the same
  pattern the Linux distros' layer manifests use). When
  the signature is real, `installFromBinary` will refuse
  to stage a binary from an unsigned spec.
- **Staging on a separate partition** — the staging
  directory is currently `<baseDir>/staging/<specId>/`.
  For VMs that mount a host directory, a future phase
  uses the mount directly (no copy step).
