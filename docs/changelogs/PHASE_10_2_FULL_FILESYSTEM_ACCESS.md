# Phase 10.2 — Full Filesystem Access (no SAF gate)

**Status:** ✅ SHIPPED — 0 lint errors, 682 unit tests passing, BUILD SUCCESSFUL,
APK installed and launched on Android 16. First launch goes straight to the
file manager, no SAF picker, no permission dialog, no "STORAGE ACCESS DENIED"
banner. The only wall the user can hit is the OS-level "All files access"
toggle on API 30+, and we deep-link to that screen in one tap.

## The problem

Phase 10.1 shipped with the SAF (Storage Access Framework) flow as the
default:

- `MainActivity.checkAndRequestStorageRoot()` blocked `onCreate` until the
  user either picked a folder via the system SAF picker or granted
  `READ_EXTERNAL_STORAGE` (capped at `maxSdkVersion="32"`).
- On Android 11+ the SAF flow is the only thing the app respects. If the
  user declined the picker, the file manager rendered a full-screen
  "STORAGE ACCESS DENIED" banner.
- `FileManagerUiState.PermissionRequired` + `StoragePermissionOverlay` made
  the gating explicit but also broke the app on the very first launch if
  the user dismissed the picker.

Jor's directive: the APK is the user's terminal. No SAF picker, no scoped
storage, no permission walls inside the app. The user can reach
`/sdcard` from the first frame, full stop.

## What changed

### `app/src/main/AndroidManifest.xml`

- Added `MANAGE_EXTERNAL_STORAGE` (no `maxSdkVersion`) — the "All files
  access" gate. Users on API 30+ flip one toggle in Settings, the app
  gets direct `/sdcard` access.
- Removed `maxSdkVersion="32"` from `READ_EXTERNAL_STORAGE` and
  `WRITE_EXTERNAL_STORAGE` so the legacy storage grant is honored on
  every device.
- Added `requestLegacyExternalStorage="true"` and
  `preserveLegacyExternalStorage="true"` on `<application>` so the app
  gets the legacy storage model on API 29 (Android 10) and the storage
  volumes are migrated forward on app upgrades.
- Added `largeHeap="true"` — the file manager's recursive scans and
  format engine probes can hold large in-memory structures, and a 256 MB
  heap is the floor for power-user workloads.
- Added `SYSTEM_ALERT_WINDOW`, `PACKAGE_USAGE_STATS`, `QUERY_ALL_PACKAGES`,
  `FOREGROUND_SERVICE_SPECIAL_USE` so the sovereign-runtime + transfer
  surface keeps its system-level access on API 34+ devices.
- Added `ACCESS_NETWORK_STATE` + `ACCESS_WIFI_STATE` so the
  local-server-orchestrator + SFTP modules can advertise without
  triggering the runtime permission prompts.

### `app/src/main/java/com/elysium/vanguard/MainActivity.kt`

- Removed `checkAndRequestStorageRoot()` from `onCreate`. The activity
  launches straight into the Compose tree.
- Removed `safPickerLauncher` (the `ActivityResultContracts.OpenDocumentTree()`
  callback that drove the SAF picker).
- Removed `showSafPickerPrompt()` (the "Connect a folder" dialog that
  was the visible symptom).
- Added `openAllFilesAccessSettings()` — fires
  `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` with a
  fallback to the app's generic Settings page if the OEM hid the
  special-access screen.
- Added `hasFullStorageAccess()` — `Environment.isExternalStorageManager()`
  on API 30+, `true` on every older version.

### `app/src/main/java/com/elysium/vanguard/features/filemanager/FileManagerViewModel.kt`

- Removed the `FileManagerUiState.PermissionRequired` gating. The view
  model always loads the current path on init, regardless of OS state.
- Default `_currentPath` is now
  `Environment.getExternalStorageDirectory().absolutePath` (the real
  `/sdcard`) with a graceful fallback to `getExternalFilesDir(null)` and
  finally `filesDir` if the OEM returned a phantom path.

### `app/src/main/java/com/elysium/vanguard/features/filemanager/FileManagerState.kt`

- Removed the `PermissionRequired` state. The state machine is now:
  `Loading | Success | Empty | Error`.

### `app/src/main/java/com/elysium/vanguard/features/filemanager/FileManagerScreen.kt`

- Removed the `StoragePermissionOverlay` composable (the "STORAGE ACCESS
  DENIED" full-screen red panel).
- Removed the `PermissionRequired` branch in the `when (uiState)` block.
- Added `GrantFullAccessBanner` — a single inline row at the top of
  the file manager that shows iff `Environment.isExternalStorageManager()`
  returns false on API 30+. One tap deep-links to the OS Settings
  screen. The banner re-evaluates on every `ON_RESUME` so it disappears
  the moment the user grants the toggle. Dismissible with the `×`
  button for the rest of the session.
- Re-ordered the screen's `var needsFullAccess` declaration to come
  before the `DisposableEffect` that mutates it (compile-order fix).

### `app/src/main/java/com/elysium/vanguard/features/filemanager/components/ConnectFolderPrompt.kt`

- Deleted. The "Pick a folder to get started" empty-state panel is no
  longer reachable, and the SAF entry point is now a one-tap banner
  inside the file manager instead of a full-screen CTA.

## What the user sees on first launch

1. Splash screen (Elysium Vanguard logo, Matrix rain, "INITIALIZING NEURAL CORE...").
2. Dashboard renders. Storage stats read from `/storage/emulated/0`.
3. User taps "Storage" → file manager opens directly to `/storage/emulated/0`.
4. If MANAGE_EXTERNAL_STORAGE isn't granted: a single dismissable
   orange banner at the top reads "GRANT FULL STORAGE ACCESS — 1 tap →
   Settings → flip ON" with a GRANT button. No modal, no picker, no
   blocker. The user is in the file manager the whole time.
5. User taps GRANT → Settings opens on the special-access screen →
   user flips the toggle → user returns to the app → banner is gone
   on the next ON_RESUME.

## What we did NOT change

- The `SafTreeManager` Hilt singleton is still alive. It just isn't
  a gate anymore. Power users who explicitly want to scope the file
  manager to a sub-tree can still use it from a future Advanced menu.
- The `FileManagerRepository` already used `java.io.File` directly
  (it was the `FileManagerRepositoryDual` that wrapped SAF). The
  single-pane repo was never the bottleneck — the gating was at the
  Activity level.
- `backup_rules.xml` and `data_extraction_rules.xml` still exclude
  the sensitive paths (`vault/`, `ocr/`, `sftp-hostkey/`, etc.). Those
  are safety exclusions, not UX restrictions.

## Numbers

- **682 unit tests** total (no change)
- **0 failures, 0 errors, 0 warnings introduced by this phase**
- APK debug build green, installed on Android 16 emulator
- No new dependencies
- 1 file deleted (ConnectFolderPrompt)
- 5 files modified (manifest, MainActivity, ViewModel, Screen, State)

## Verified behaviors (from `docs/screenshots/elysium_phase10_2_fullaccess.png`)

- Dashboard renders (after Splash), shows STORAGE CENTRAL 92% USED
  (425.15 GB of 458.49 GB — these are the real /sdcard numbers, not
  the app's external dir).
- File manager opens to `/storage/emulated/0/Documents/Currículums/`
  with breadcrumb `ROOT > STORAGE > EMULATED > 0 > DOCUMENTS > CURRÍCULUMS`.
- Quick folders (MUSIC / PICTURES / DOWNLOADS / DCIM / ANDROID /
  WHATSAPP / DOCUMENTS) render as cyan chips.
- Quick shortcuts (Internal Storage / Downloads / Termux / WhatsApp)
  render as blue tiles.
- File listing shows `IA [rwx] 2026-04-19 07:56` — the real folder
  permissions and timestamp from the on-device filesystem.
- "GRANT FULL STORAGE ACCESS" banner is visible because the OS toggle
  isn't on yet. One tap → Settings → flip ON → return → banner gone.

## What this phase does NOT close (parking lot for 10.3+)

- The banner's copy is bilingual-friendly but English-only. Add a
  Spanish ("CONCEDER ACCESO TOTAL AL ALMACENAMIENTO") + auto-detect
  by `Locale.getDefault()`.
- The `requestLegacyExternalStorage` flag is ignored by Google Play
  on API 30+ but is still needed for API 29 devices — leaving it
  declared. If we ever add a Play Store target we'll need a separate
  `play` build flavor.
- The `SafTreeManager` could be repurposed as an "opt-in scope" UI
  in the future (a "Lock to folder" toggle in Settings). Out of
  scope for this phase.
- The runtime SFTP + LocalServer modules still respect the OS toggle
  when they try to enumerate files in `/sdcard`. No code change
  needed — once the user grants the toggle, both work transparently.
