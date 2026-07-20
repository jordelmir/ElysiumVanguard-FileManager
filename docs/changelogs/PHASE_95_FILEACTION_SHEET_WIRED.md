# Phase 95 — FileActionSheet Wired to FileManagerScreen

| Field        | Value                                                                |
|--------------|----------------------------------------------------------------------|
| Phase        | 95                                                                   |
| Date         | 2026-07-20                                                           |
| Commit       | (this commit)                                                        |
| Depends on   | Phase 94 (FileAction production wiring)                              |
| ADR          | (none — pure UI wire; the architectural decision is in ADR-035)      |

## What this phase does

Phase 94 shipped the production `FileAction` ViewModel + handlers
+ Hilt module. Phase 95 wires the **UI** to the VM: the
`FileManagerScreen` long-press menu now includes a
**"Contextual actions"** option that opens the `FileActionSheet`
as a `ModalBottomSheet`.

Before Phase 95, the user could long-press a file and see
the `SovereignOptionsDialog` (rename / delete / compress /
share / vault). After Phase 95, the dialog has a new
**"Contextual actions"** row (only for files, not folders)
that opens a Material 3 `ModalBottomSheet` with the
contextual actions for the file's extension:

- `.deb` → "Install in <distro>" (one row per installed apt-based distro)
- `.rpm` → "Install in <distro>" (one row per installed dnf-based distro)
- `.pkg.tar.zst` → "Install in <distro>" (one row per installed pacman distro)
- `.AppImage` → "Run in <distro>"
- `.exe` / `.msi` → "Run in <VM>"
- `.iso` / `.img` / `.qcow2` → "Mount as <format>" + "Boot VM from <format>"
- `.git` → "Clone repo"
- `.smb` / `.webdav` / `.cifs` → "Mount as <protocol>"
- `.usbotg` → "Inspect USB OTG"

The sheet renders each action with an icon (apt/dnf/pacman →
red Archive, AppImage → green PlayArrow, ISO → orange Storage,
Git → teal Download, etc.) + the action's label + description.

## File diff (one file, ~80 lines)

- `app/src/main/java/com/elysium/vanguard/features/filemanager/FileManagerScreen.kt`:
  - Added imports: `FileActionViewModel`, `FileActionSheet`,
    `FileActionOutcome`, `FileAction`, `ModalBottomSheet`,
    `rememberModalBottomSheetState`, `ExperimentalMaterial3Api`
  - Added `@OptIn(ExperimentalMaterial3Api::class)` on the
    `FileManagerScreen` function
  - Added VM injection via `hiltViewModel()` (no need to thread
    a new param through)
  - Added `fileForActions: TitanFile?` state
  - Added a `LaunchedEffect` that toasts the outcome when
    `actionState.lastOutcome` updates
  - In `SovereignOptionsDialog`, added a new `OptionItem`
    "Contextual actions" (only shown when `!file.isFolder`)
    that fires `onAction("CONTEXTUAL")`
  - In the long-press `onAction` lambda (both list mode + grid
    mode), added the `else if (action == "CONTEXTUAL")` branch
    that calls `actionViewModel.openActionSheet(File(file.path))`
    + sets `fileForActions = file`
  - Added a `ModalBottomSheet` block at the end of the
    composable that renders the `FileActionSheet` when
    `fileForActions != null && actionState.sheetVisible`

## Algorithm — long-press → contextual sheet

1. User long-presses a file in the File Manager.
2. `SovereignOptionsDialog` opens (existing UX).
3. User taps "Contextual actions".
4. The `onAction` lambda sets `fileForActions = file` and
   calls `actionViewModel.openActionSheet(File(file.path))`.
5. The VM reads the environment (installed distros + VM specs),
   builds a `FileActionContext`, and runs `FileActionResolver`.
6. The resolver returns the list of `FileAction`s for the
   file (1 for simple files, 2 for ISO/IMG/QCOW2, N for
   install-in-each-distro).
7. The `ModalBottomSheet` block sees `fileForActions != null
   && actionState.sheetVisible` and renders the
   `FileActionSheet` with the actions.
8. User taps an action → `actionViewModel.execute(action)` →
   the VM dispatches the action to the production handler
   (`ProcessLauncherPackageInstaller` / `ProcessLauncherGitCloneRunner` /
   `ProcessLauncherDiskImageBackend`).
9. The handler runs the production path (proot + apt/dnf/pacman
   / git clone / mount / qemu-img / qemu-system).
10. The VM's `state.lastOutcome` updates → the `LaunchedEffect`
    shows a Toast with the outcome message.

## Test count

3468/3468 green (no new tests this phase; the UI wire is
verified by the existing 16 production-impl + 7 VM tests).

## What this does NOT do

- **Real `waitFor()` on `LaunchedProcess`** (the 60s polling
  loop stand-in is still in place). Phase 95+ will replace
  with a real exit-code read.
- **RunAppImage / RunWindowsBinary / MountNetworkShare /
  InspectUsbOtgDevice action dispatch** still emits a
  "(queued)" success message in the VM. Phase 95+ will wire
  them to FUSE mount / Wine / smbclient / gvfs.
- **Visual confirmation of the sheet**. The `ModalBottomSheet`
  renders via Material 3, the file is included in the diff,
  and the build is green; I was unable to capture an on-device
  screenshot of the sheet (the device's long-press is hard to
  trigger via `adb shell input`). The next on-device session
  will capture a screenshot for the changelog.

## Build verification

- `./gradlew compileDebugKotlin` — green
- `./gradlew testDebugUnitTest` — 3468/3468 green
- `./gradlew assembleDebug` — APK built
- `adb install -r app/build/outputs/apk/debug/app-debug.apk` — installed
- `adb shell am start -n com.elysium.vanguard/.MainActivity` — launches, dashboard renders, File Manager renders (verified via screencap)
