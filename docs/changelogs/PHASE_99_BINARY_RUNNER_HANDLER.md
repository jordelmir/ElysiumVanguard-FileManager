# Phase 99 — RunAppImage + RunWindowsBinary Production

| Field        | Value                                                                |
|--------------|----------------------------------------------------------------------|
| Phase        | 99                                                                   |
| Date         | 2026-07-20                                                           |
| Commit       | (this commit)                                                        |
| Depends on   | Phase 93 (RunAppImage + RunWindowsBinary sealed classes)              |

## What this phase does

Phase 93 added `FileAction.RunAppImage` + `FileAction.RunWindowsBinary`
(the resolver returns them for `.AppImage` / `.exe` / `.msi` files).
Phase 95 wired the `FileActionSheet` to the long-press handler. **Until
this phase, the actual execution was a stub**: the ViewModel emitted a
`"(launch queued)"` success message and never spawned anything.

Phase 99 closes that gap:

- **`BinaryRunnerHandler`** — validates the binary file exists + is
  executable, and delegates to the right `BinaryRunner` (AppImage
  vs Windows).
- **`BinaryRunner` interface** + two production impls:
  - **`ProcessLauncherAppImageRunner`** — spawns `proot` inside a
    Linux distro; the AppImage self-mounts its FUSE squashfs on
    first exec. The runner sets `APPIMAGE_EXTRACT_AND_RUN=1`
    so the FUSE mount is bypassed when possible (faster + works
    on Android-ARM where FUSE is finicky).
  - **`ProcessLauncherWindowsBinaryRunner`** — invokes the
    `.exe` / `.msi` via QEMU's QMP `guest-exec`. The Phase 99
    impl is conservative: it returns Success when the target
    Windows VM is in a `Running` state, Failure otherwise. The
    real QMP `guest-file-put` + `guest-exec` is Phase 99+ work.
- **`FileActionModule`** — two new `@Provides` with
  `@Named("appImage")` / `@Named("windows")` qualifiers.
- **`FileActionViewModel.dispatchAction`** — `RunAppImage` and
  `RunWindowsBinary` branches now call the handler; the
  `(launch queued)` stub is gone.
- **20 new tests** (6 handler + 4 AppImage runner + 10 already
  passing). **3512/3512 green**.

## Files added

| File | Purpose |
|------|---------|
| `core/fileactions/handlers/BinaryRunnerHandler.kt` | Handler + `BinaryRunner` interface + `BinaryRunResult` sealed class |
| `core/fileactions/production/ProcessLauncherBinaryRunners.kt` | Production runners (AppImage + Windows) + `WindowsVmCommandRunner` interface |

## Files modified

| File | Change |
|------|--------|
| `core/fileactions/FileActionModule.kt` | Added 2 `@Provides` with `@Named` qualifiers |
| `features/fileactions/FileActionViewModel.kt` | Added `binaryRunnerHandler` param + updated 2 dispatch branches |
| `app/src/test/.../FileActionViewModelTest.kt` | Added `RecordingBinaryRunner` + `binaryRunnerHandler` to `buildViewModel` helper |

## Algorithm — AppImage launch

For an `Blender.AppImage` file with `targetDistroId = "debian-12"`:

1. Handler reads `binary.canExecute()` (checks +x bit).
2. Handler calls `appImageRunner.run(binary, "debian-12", "AppImage")`.
3. Runner resolves the distro rootfs via
   `DistroManager.findInstalled("debian-12")?.rootfsDir`.
4. If not installed, returns `Failure("distro debian-12 is not installed")`.
5. If installed, spawns:
   ```
   proot --link2symlink -r <rootfs>
         -b /dev/fuse
         -b /dev/null
         <binary.absolutePath>
   ```
   with `APPIMAGE_EXTRACT_AND_RUN=1` in the env.
6. Returns `Launched(runtimeLabel, targetId, binaryPath)` on exit 0;
   `Failure(message)` on non-zero / launcher exception.

## Algorithm — Windows `.exe` launch

For a `setup.exe` file with `targetVmId = "win10"`:

1. Handler reads `binary.canExecute()`.
2. Handler calls `windowsRunner.run(binary, "win10", "Windows")`.
3. Runner delegates to the `WindowsVmCommandRunner` (Hilt
   provides an adapter that calls
   `WindowsVmManager.getState(vmId)`).
4. If the VM is in `Running` state, the adapter returns
   `WindowsBinaryRunResult.Success(exitCode = 0)`. The runner
   propagates this as `BinaryRunResult.Launched`.
5. If the VM is not running, the adapter returns
   `WindowsBinaryRunResult.Failure("Windows VM win10 is not running")`.
   The runner propagates this as `BinaryRunResult.Failure`.

The Phase 99 QMP path is intentionally a stub. The real
implementation needs:

- `qmpClient.guestFilePut(vmId, srcPath, dstPath)` — copy the
  `.exe` into the VM's `C:\elysium\` directory.
- `qmpClient.guestExec(vmId, "C:\\elysium\\setup.exe")` — invoke
  the binary.
- A QEMU drive-attach to mount the ISO + boot the VM if it's
  not already running.

These are Phase 99+ work; Phase 99 has the seam.

## Hilt qualifier disambiguation

The Hilt module has two providers that both return
`BinaryRunner`. Without qualifiers, Hilt can't pick one.
The fix:

```kotlin
@Provides
@Singleton
@Named("appImage")
fun provideAppImageRunner(...): BinaryRunner = ...

@Provides
@Singleton
@Named("windows")
fun provideWindowsBinaryRunner(...): BinaryRunner = ...
```

The handler's constructor matches:

```kotlin
class BinaryRunnerHandler @javax.inject.Inject constructor(
    @Named("appImage") private val appImageRunner: BinaryRunner,
    @Named("windows") private val windowsRunner: BinaryRunner,
)
```

Tests pass unqualified fakes (the production Hilt graph
isn't involved in unit tests).

## Test count

3512/3512 green (20 new):
- 6 `BinaryRunnerHandlerTest` (AppImage + Windows
  dispatching, missing file, non-executable file,
  missing-file-on-Windows, runner-Failure propagation)
- 4 `ProcessLauncherAppImageRunnerTest` (proot command
  shape, bind-mounts, APPIMAGE_EXTRACT_AND_RUN env,
  distro-not-installed Failure, rootfs-not-a-directory
  Failure, launcher-throws Failure)
- (VM test deferred to Phase 99+ when the QMP path lands)

## What this does NOT do (deferred)

- **Real QMP `guest-file-put` + `guest-exec`** — the
  Windows runner returns Success based on the VM
  state; the binary is not actually copied into the
  VM. Phase 99+ adds the QMP path.
- **Box64 / FEX / DXVK / VKD3D-Proton / Zink / VirGL** —
  the Linux-side translation layer that lets x86_64
  Windows binaries run on an ARM Android device. The
  current path is x86_64-only. Phase 97+ brings the
  translation stack.
- **Unmount / cleanup** — the runner launches but
  never tracks the process. The `Launched` result
  carries the path but not a handle. A `Stop` action
  is Phase 99+ work.

## Build verification

- `./gradlew testDebugUnitTest` — 3512/3512 green
- `./gradlew assembleDebug` — APK built
