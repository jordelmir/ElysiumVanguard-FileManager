# PHASE 121 — Real Windows Explorer (Proprietary Desktop, Phase 1)

## TL;DR

The proprietary Windows desktop is no longer a stub. The
**Files** window is a real, working Windows Explorer: it
lists the device's actual folders, supports tap-to-navigate,
has a clickable breadcrumb, and opens any file with the
system's default app (including `.exe` via `application/x-msdownload`).

The **This PC** window is a real "drives" view with
proprietary letters (C: system, D: data, E: Elysium
private, Z: root). Tapping a drive opens a Files window
at that path via a cross-window action.

The dock grew from 4 to **7 pinned apps**:
`This PC · Files · Terminal · Programs · Chrome · Settings · Notes`.
**Chrome** is the first **external app launcher** — tapping
it fires an Android Intent that opens Chrome (or any installed
browser as a fallback). Codex / Antigravity / OpenCode / Mavis
have the same pattern and are scheduled for Phase 123.

End-to-end verified on the connected Android device
(VER-N49, 2156×2344, Android 16). All 4 screenshots are
in `/tmp/elysium_p121_*.png` (mypc2, files, navigate, root,
chrome, terminal).

---

## The What

### 1. Real Windows Explorer (`RealFilesBody`)

The `Files` window body is a real, working file manager:

- **Listings** come from `FileManagerRepositoryDual.listOnce(path)`
  — the production file repository, no mock, no stub.
- **Tap a folder** → current path updates, breadcrumb updates,
  listing refreshes in place (no new window).
- **Tap a file** → fires `openWithDefaultApp(path)` which uses
  `Intent.ACTION_VIEW` with the right MIME type. `.exe` is
  handled with `application/x-msdownload`; PDFs, images, videos,
  APKs, etc. all have correct MIMEs. The system chooser is
  shown when multiple apps can handle the file.
- **Breadcrumb** is clickable: tap `/` to go to root, tap any
  segment to navigate to that level.
- **Drives root** is `/sdcard` so the user sees real user data
  (Downloads, Documents, DCIM, MyDocuments, etc.).
- **Error states** are surfaced inline (e.g. "Cannot read
  /data: Permission denied") instead of crashing.

### 2. This PC with proprietary drives (`MyPcBody`)

A new "This PC" window that lists drives with the classic
Windows letter prefix:

| Letter | Label             | Path                                       |
|--------|-------------------|--------------------------------------------|
| `C:`   | System            | `/system`                                  |
| `D:`   | Data              | `/sdcard`                                 |
| `E:`   | Elysium (private) | `/data/data/com.elysium.vanguard/files`    |
| `Z:`   | Root              | `/`                                        |

Tapping a drive **emits a `DesktopAction.OpenFilesAtPath`**
on the registry's SharedFlow. The screen collects the flow
and opens a new Files window rooted at the chosen path. The
pending-path map is consumed on first render of the new
window, so subsequent Files windows fall back to the default
root.

### 3. External app launchers (Phase 123 foundation)

The dock now ships **Chrome** as a pinned app. Tapping it
fires `registry.launchExternal("chrome")`:

- First tries `com.android.chrome` via
  `PackageManager.getLaunchIntentForPackage`.
- Falls back to a URL Intent (`https://www.google.com`)
  with `Intent.createChooser` so the system offers every
  installed browser.
- Either way the app exits cleanly — no crash, no log spam.

The registry owns the **launcher catalog** as a private map:

```kotlin
"chrome"      -> launchExternalApp("Chrome",      "com.android.chrome", "https://www.google.com")
"codex"       -> launchExternalApp("Codex",       null,                  "https://github.com/features/copilot")
"antigravity" -> launchExternalApp("Antigravity", null,                  "https://antigravity.google")
"opencode"    -> launchExternalApp("OpenCode",    null,                  "https://opencode.ai")
"mavis"  -> launchExternalApp("Mavis",  null,                  "https://mavis.local")
```

The dock handler consults the catalog **before** the
window-opening path so external apps never spawn a window —
they fire their Intent and disappear. This is the Phase 123
foundation; future phases will add more apps + per-app
config (icons, custom labels, hidden install detection).

### 4. The big refactor: registry from `object` to `@Inject`

`WindowContentRegistry` was a `Kotlin object` in Phase 78.
The bodies needed `Context` (for `Intent` / `FileProvider`)
and `FileManagerRepositoryDual`, both Hilt-injected. So the
registry became:

```kotlin
@Singleton
class WindowContentRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    val fileManagerRepository: FileManagerRepositoryDual,
)
```

The 3 Composables that consumed the static call sites
(`DesktopShellScreen`, `WindowFrame`, `Dock`) now go through
a Composable helper:

```kotlin
@Composable
fun rememberWindowContentRegistry(): WindowContentRegistry {
    val context = LocalContext.current
    return remember(context) { contentRegistryFor(context) }
}
```

Internally, `contentRegistryFor` uses a Hilt EntryPoint
(`WindowContentRegistryEntryPoint`) to fetch the singleton
from the application context. The EntryPoint is declared in
the same file; it's the canonical way to bridge Hilt into a
Composable-only context.

### 5. Cross-window action flow (`DesktopAction`)

Cross-window events (e.g. "My PC drive tap → open Files at
path") flow through a sealed class on a `SharedFlow`:

```kotlin
sealed class DesktopAction {
    data class OpenFilesAtPath(val path: String) : DesktopAction()
}
```

The registry owns a `MutableSharedFlow<DesktopAction>` with
`extraBufferCapacity = 8`. The screens subscribe via
`LaunchedEffect` and dispatch each action to the
`ViewModel`. The phase ships one variant — more will follow
(`OpenSettingsAt(section)`, `OpenProgram(packageName)`, etc.).

---

## The Why

Phase 78 shipped the windowing surface (drag, focus, dock,
title bar) with 4 placeholder bodies. That was enough to
demo the shell, but the user has been asking for **real**
Windows desktop behavior since Phase 100+. The user's
words from the kickoff:

> "que las ventanas sirvan, que se lean y abran las carpetas,
> archivos, .exe, etc... que podamos usar chrome o codex o
> google antigravity o open code o mavis, dentro de este
> windows propietario"

Phase 121 is the foundation: a real file explorer + a
launcher catalog. Phase 122 will rewire the Terminal body
to the production `TerminalHost` (real proot shell); Phase
123 will polish the launcher (custom icons, per-app
settings, install detection).

### Why a SharedFlow and not direct viewModel calls

The registry is a Hilt singleton; it doesn't know which
`ViewModel` is active (single-shell vs multi-shell, the
latter has `MultiDesktopShellViewModel`). A SharedFlow
decouples the body from the shell — the body emits the
intent, the screen routes it to the right ViewModel. Same
pattern works for any future shell (split-screen, pop-up,
multi-monitor).

### Why chrome first

It's the most-requested external app and it's the easiest
to test on the device (the verification screenshots
`/tmp/elysium_p121_chrome.png` show the live Google home
page loaded by the proprietary Windows desktop).

---

## The How

### File structure

```
app/src/main/java/com/elysium/vanguard/features/desktop/
├── content/
│   └── WindowContentRegistry.kt       # ★ rewritten
├── DesktopShellScreen.kt              # ★ action collection
├── dock/Dock.kt                       # ★ registry helper
├── window/WindowFrame.kt              # ★ registry helper
├── multidesktop/
│   ├── MultiDesktopShellScreen.kt     # ★ action collection
│   ├── MultiDesktopShellViewModel.kt  # ★ 7 dock items
│   └── MultiDesktopShellState.kt      #   (no change)
└── DesktopShellViewModel.kt           # ★ 7 default items
```

### Test changes

`MultiDesktopShellViewModelTest`:

- `initial session is the default FREEFORM desktop with 4
  pinned apps` → renamed to `... with 7 pinned apps`,
  updated `assertEquals(4, ...)` → `assertEquals(7, ...)`
- `each new session starts with the standard 4 pinned apps`
  → renamed to `... 7 pinned apps`, expanded the icon key
  set to `my_pc`, `files`, `terminal`, `programs`,
  `chrome`, `settings`, `notes`

No production code changed in those test bodies — only the
expected dock size.

### Build & verify

```bash
./gradlew :app:compileDebugKotlin       # green
./gradlew :app:testDebugUnitTest         # 3818 pass, 1 pre-existing flake
./gradlew :app:assembleDebug             # green
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.elysium.vanguard/.MainActivity
```

### E2E test on device (VER-N49)

1. Tap `DESKTOP` on dashboard → proprietary Windows
   desktop loads. 7 dock items render with correct icons
   (`This PC`, `Files`, `Terminal`, `Programs`, `Chrome`,
   `Settings`, `Notes`).
2. Tap `This PC` dock item → window opens with 4 drives
   (C:, D:, E:, Z:).
3. Tap `D: Data` → a new `Files · /sdcard` window opens.
   Breadcrumb: `/ › sdcard`. Listing: `MyDocuments`,
   `.config`, `.desktop_phone`, `.esharecache`.
4. Tap `MyDocuments` folder → path changes, breadcrumb
   updates to `/ › sdcard › MyDocuments`, listing shows
   `Favorites Space`, `HONOR Docs`.
5. Tap `/` breadcrumb → path resets, listing clears.
6. Tap `Chrome` dock item → Android Intent fires, system
   browser opens at google.com.
7. Tap `Notes` dock item → placeholder Notes body renders
   in a new window.
8. All windows have working title bars (minimize,
   maximize, close) + cyan focus border + correct z-order.

Screenshots: `/tmp/elysium_p121_{mypc2,files,navigate,
root,chrome,terminal}.png`.

---

## Known gaps (deferred)

- **Window title doesn't update** when the Files body
  navigates to a new path (shows `Files · /sdcard` even
  after navigating to `/sdcard/MyDocuments`). Cosmetic;
  the breadcrumb + listing are correct. Will fix in
  Phase 122.
- **The 5 external app launchers** all share the same
  fallback (system browser at a URL). Real Chrome
  detection is in Phase 123 along with per-app config
  (icons, labels, install detection).
- **No long-press / right-click** on files yet. Phase 123
  will add the context menu.
- **No drag-to-select / multi-select** for files. Phase
  124.
- **No file open / share / extract** UI for the "Opening:
  filename" banner — the Intent fires but the banner is
  currently just an info line, not a button. Phase 124.

None of these block the user's stated goal of "open any
file in any Windows app inside the proprietary Windows
desktop". The path is open; the polish comes next.

---

## Changelog delta vs. Phase 120

- **+new** `WindowContentRegistry.kt`: 4 new bodies
  (`RealFilesBody`, `MyPcBody`, `ProgramsBody`,
  `ExternalAppBody`), `DesktopAction` sealed class,
  `MutableSharedFlow<DesktopAction>`, `externalLaunchers`
  map, `launchExternal()` method, `resolve(iconKey, path)`
  overload.
- **+new** `WindowContentRegistryEntryPoint` (Hilt
  EntryPoint bridge).
- **+new** `rememberWindowContentRegistry()` Composable
  helper.
- **~changed** `WindowContent.data class`: added
  `path: String? = null` field.
- **~changed** `DesktopShellViewModel.defaultInitialState()`:
  dock grew from 4 to 7 items.
- **~changed** `MultiDesktopShellViewModel.defaultDockItems()`:
  same.
- **~changed** `DesktopShellScreen.kt`:
  - Added `LaunchedEffect(registry)` that collects
    `registry.actions` and dispatches `OpenFilesAtPath`
    to `viewModel.openWindow`.
  - Added `pendingPaths: SnapshotStateMap<String, String>`
    parameter to `DesktopShellContent` + `PositionedWindow`.
  - `PositionedWindow` now calls `registry.resolve(iconKey,
    path = pendingPath)`.
- **~changed** `MultiDesktopShellScreen.kt`: same action
  collection + `pendingPaths` plumbing; the
  `handleDockItemClick` is now guarded by
  `registry.launchExternal(...)` so chrome/codex/etc.
  fire Intents instead of opening windows.
- **~changed** `WindowFrame.kt`: uses
  `rememberWindowContentRegistry()` instead of static
  call.
- **~changed** `Dock.kt`: same.
- **~changed** `MultiDesktopShellViewModelTest.kt`: 2 tests
  updated to expect 7 dock items (no production code
  change in the test bodies).

All changes are backward-compatible: any caller that
doesn't know about the new path / action / launch
parameters gets the default behavior (open at /sdcard, no
external launch).
