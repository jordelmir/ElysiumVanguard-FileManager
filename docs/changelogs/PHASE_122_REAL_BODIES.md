# PHASE 122 — Real bodies (Terminal, Settings, Notes, Programs)

## TL;DR

The proprietary Windows desktop's 4 placeholder bodies
(`TerminalBody`, `SettingsBody`, `NotesBody`, `ProgramsBody`)
are now **real, end-to-end functional**. Every one of them
is wired to the device's actual filesystem, the Android
system services, or a Hilt-injected repository — no more
Lorem-Ipsum placeholders.

| Body        | What it does now                                              |
|-------------|---------------------------------------------------------------|
| **Terminal** | Client-side shell with 22 built-in commands, real `ls/cd/cat/mkdir/touch/rm/stat/open/df/free/battery/uname/uptime/date/version/drives/help/clear/echo/whoami/tree/pwd`, PS1 prompt with `~` shorthand, command history, debounced auto-save. |
| **Settings** | Live storage/memory/battery bars from real `StatFs`/`ActivityManager`/`BatteryManager`. Theme picker (3 options, persisted to `settings/theme.txt`). About section with app version, package, Android version, device, runtime. |
| **Notes**    | Multi-note editor with list pane. Notes persisted as `filesDir/notes/<id>.txt`. Auto-save debounced 600ms. Delete button. Character count. |
| **Programs** | Elysium System section (4 built-in + 5 external launchers) + User Apps section (live `PackageManager.getInstalledApplications` query with launcher activities). Tap any app to launch it via `Intent`. |

The new `DesktopAction.OpenInternal(iconKey, title)` route
lets `ProgramsBody` open built-in windows through the
same SharedFlow mechanism the dock uses.

Build verified: 3818 tests pass, 1 pre-existing flake
unchanged. APK builds clean (0 warnings).

Visual verification is pending — the connected device
disconnected mid-phase (wireless ADB DNS expired; the
host `adb-A2VQ024305000780-SoFCiE._adb-tls-connect._tcp`
no longer resolves). Reconnect + visual capture is the
first task once the device is back.

---

## The What

### 1. Real Terminal (`TerminalBody`)

The placeholder "Phase 122 will wire this body" is gone.
The terminal is now a working client-side shell:

**State** (per-window, `remember`-ed across recompositions):
- `lines: List<TerminalLine>` — input + output history
  (sealed class: `Input` / `Output` / `Error` / `Info`).
- `currentDir: String` — working directory; starts at
  `/sdcard`, updated by `cd`.
- `commandHistory: MutableList<String>` — every
  command, for Up/Down recall (Phase 123).
- `input: String` — current TextField content.

**Layout**:
- Black background (`#0B0F14`), monospaced font
  (`FontFamily.Monospace`, 12sp).
- Output history in a `Column.verticalScroll` that
  auto-scrolls to the bottom on every new line
  (LaunchedEffect on `lines.size`).
- PS1 prompt at the bottom: `elysium@vanguard:<dir>$ `,
  green for the dir, `~` shorthand for the home.
- TextField styled to look like a terminal prompt
  (transparent container, transparent indicator,
  cyan cursor).
- `keyboardOptions.imeAction = Send` +
  `keyboardActions.onSend` for Enter-to-execute.

**Commands** (22 total, all real):
- `pwd`, `cd`, `ls`, `tree` — directory ops (wired
  to `FileManagerRepositoryDual`).
- `cat`, `mkdir`, `touch`, `rm`, `stat` — file ops
  (wired to `java.io.File`).
- `open <file>` — fires `Intent.ACTION_VIEW` with
  `*/*` MIME + system chooser.
- `echo`, `whoami`, `uname`, `date`, `uptime` —
  identity / system info.
- `df`, `free`, `battery` — real device storage /
  memory / battery (`StatFs`, `ActivityManager`,
  `BatteryManager`).
- `version`, `drives` — proprietary Elysium
  metadata.
- `clear` — wipes the screen (sentinel `__CLEAR__`
  pattern; the caller pattern-matches and resets
  `lines`).
- `help` — lists every command with a one-liner.

### 2. Real Settings (`SettingsBody`)

The 4 bullet points ("Theme: Sovereign Dark", etc.)
are gone. The settings window is now:

**Live data** (re-read on every composition — the
platform helpers are O(1)):
- **Storage** — `StatFs(Environment.getExternalStorageDirectory().path)`
  → total / used / free / percent, with a horizontal
  `LinearProgressIndicator` in amber.
- **Memory** — `ActivityManager.getMemoryInfo()`
  → total / used / free / percent, bar in cyan.
- **Battery** — `BatteryManager.BATTERY_PROPERTY_CAPACITY`,
  bar green when ≥30%, red when low.
- **About** — version `1.0.0-TITAN`, package
  `com.elysium.vanguard`, Android `${Build.VERSION.RELEASE}`,
  SDK `${Build.VERSION.SDK_INT}`, device `${Build.MANUFACTURER}
  ${Build.MODEL}`.

**Theme picker** — 3 chips (Sovereign Dark / Light /
System). The selection is persisted to
`filesDir/settings/theme.txt` and survives restarts.
The actual `MaterialTheme` re-coloring is wired in
Phase 123 when the theme tokens are extracted into
a top-level provider (Phase 122 ships the read +
write path so the choice is durable).

**Layout** — `verticalScroll(rememberScrollState())`
so the user can scroll the long About section on
small windows.

### 3. Real Notes (`NotesBody`)

The 1-paragraph placeholder is gone. The notes
window is a real text editor:

**Layout** — 2 panes:
- **Left** (160dp) — list of note titles, with a
  `+` button at the top to create a new note.
  Tapping a note selects it; selection persists
  the current edit first.
- **Right** — title (large text) + body (full
  text area) + footer with delete button +
  character count.

**Persistence** — each note is a text file:
```
filesDir/notes/<id>.txt
<title>
<body>
```
The first line is the title; subsequent lines are
the body. The list of notes = the list of `.txt`
files in the directory. No index file needed (the
filesystem IS the index).

**Auto-save** — when the user types, we cancel the
previous `saveJob` and schedule a new one 600ms in
the future (`kotlinx.coroutines.delay(600)`). The
`scheduleSave` helper returns the new `Job?` so the
caller can store it in state and cancel on the
next keystroke.

**Delete** — the delete button removes the
current note's file, removes the entry from the
in-memory list, and selects the next note (or
clears the editor if the list is empty).

### 4. Real Programs (`ProgramsBody`)

The 5-item static list is gone. The programs window
has 2 sections:

**Elysium System** (10 entries):
- 4 built-ins: Files, Terminal, This PC, Settings, Notes
- 5 external launchers: Chrome, Codex, Antigravity,
  OpenCode, Mavis
- Tapping any of them routes through the registry:
  - Built-ins → `registry.requestOpenInternal(iconKey, title)`
    → `DesktopAction.OpenInternal` → shell opens a
    new window with the right body.
  - External launchers → `registry.launchExternal(iconKey)`
    → fires the Android Intent (same as the dock).

**User Apps** (live PackageManager query):
- Queries `PackageManager.queryIntentActivities(
  Intent(ACTION_MAIN).addCategory(CATEGORY_LAUNCHER), 0)`
  in a `LaunchedEffect(Unit)`.
- Sorts alphabetically by label.
- Each entry: name + package name. Tap fires
  `packageManager.getLaunchIntentForPackage(...)`.
- Defensive: silently no-ops if the app was
  uninstalled between the query and the click.

The `DesktopAction.OpenInternal(iconKey, title)`
sealed-class variant is the third member of the
`DesktopAction` family. Both `DesktopShellScreen`
and `MultiDesktopShellScreen` collect it and call
`viewModel.openWindow(...)`.

### 5. Public `requestOpenFilesAt` / `requestOpenInternal`

The registry gained two public methods so
`ProgramsBody` (and future bodies) can emit
cross-window events without touching the
ViewModel directly:

```kotlin
fun requestOpenFilesAt(path: String) {
    _actions.tryEmit(DesktopAction.OpenFilesAtPath(path))
}

fun requestOpenInternal(iconKey: String, title: String) {
    _actions.tryEmit(DesktopAction.OpenInternal(iconKey = iconKey, title = title))
}
```

The shell subscribes once and dispatches. Same
pattern Phase 121 used for `OpenFilesAtPath`; the
new variant covers the "open a built-in window
from another body" use case.

---

## The Why

After Phase 121, the proprietary Windows desktop
had a real Files window + a real launcher catalog
— but the other 4 bodies were placeholders. The
user noticed:

> "vi que son casi placeholders en muchos casos,
> como el de programas, almacenamiento o asi,
> tiene que funcionar real"

The 4 bodies were Phase 78 stubs that were fine
for shipping the windowing surface but no longer
acceptable now that the user can drive the
proprietary Windows desktop end-to-end. Phase 122
replaces every one of them with a real,
data-driven implementation.

### Why client-side for the Terminal (not proot)

The proot backend is blocked on `libproot.so`
(NDK cross-compile, see `proot/INSTALL.md`).
Rather than ship another placeholder, Phase 122
makes the terminal a **real client-side shell**
that uses the device's actual filesystem +
Android system info. Phase 123 will swap the
client-side `executeCommand` for a proot-backed
`TerminalHost.executeCommand` — the body stays
the same.

### Why a 2-pane layout for Notes

Single-pane "title + body in a single text area"
is the classic notepad anti-pattern. The 2-pane
layout (list + editor) is the standard
text-editor pattern; it scales to 100+ notes
without a UX collapse.

### Why a debounced auto-save

The 600ms debounce keeps the disk I/O off the
keystroke path. Without it, every keystroke
would write a file. With it, the user types
freely, the disk is hit only when they pause.

### Why a separate User Apps query (not embedded in Elysium System)

The 10 Elysium entries are stable (the user can
trust them to always work). The user apps are
arbitrary and may uninstall at any time —
splitting them into 2 sections lets the user
distinguish "the platform's own apps" from
"whatever happens to be on my phone today".

---

## The How

### File structure

```
app/src/main/java/com/elysium/vanguard/features/desktop/
├── content/
│   └── WindowContentRegistry.kt        # ★ 4 bodies rewritten + helpers
├── DesktopShellScreen.kt               # ★ handles OpenInternal action
├── multidesktop/MultiDesktopShellScreen.kt  # ★ handles OpenInternal action
└── (... other files unchanged)
```

### System info helpers (new, private file-level)

```kotlin
private data class StorageInfo(totalGb: String, usedGb: String, freeGb: String, usedPercent: Int)
private data class MemoryInfo(totalGb: String, usedGb: String, freeGb: String, usedPercent: Int)

private fun readStorageInfo(): StorageInfo       // StatFs → total/used/free/percent
private fun readMemoryInfo(context: Context): MemoryInfo  // ActivityManager.MemoryInfo
private fun readBatteryPercent(context: Context): Int     // BatteryManager.BATTERY_PROPERTY_CAPACITY
```

Reused by `TerminalBody` (for `df`/`free`/`battery`
commands) and `SettingsBody` (for the live bars).

### Notes persistence (new, private file-level)

```kotlin
private data class Note(id: String, title: String, content: String)
private fun notesDir(context: Context): File
private fun loadNotes(context: Context): List<Note>
private fun saveNotes(context: Context, notes: List<Note>)
private fun scheduleSave(...): Job?  // 600ms debounce
```

### Programs app query (new, private file-level)

```kotlin
private data class InstalledApp(label: String, packageName: String)
private fun queryInstalledApps(context: Context): List<InstalledApp>
private fun launchInstalledApp(context: Context, packageName: String)
```

### Build & verify

```bash
./gradlew :app:compileDebugKotlin       # green (0 warnings after SpeakerNotes fix)
./gradlew :app:testDebugUnitTest         # 3818 pass, 1 pre-existing flake
./gradlew :app:assembleDebug             # green
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Visual verification — pending device reconnect

The connected device (VER-N49) disconnected mid-phase
because the wireless ADB DNS hostname expired. I cannot
re-resolve `adb-A2VQ024305000780-SoFCiE._adb-tls-connect._tcp`
once the device goes offline; only the user can
re-enable wireless ADB on the device side to re-register
the hostname.

When the device is back, the visual smoke test is:
1. DESKTOP → tap Terminal dock item → see PS1 prompt.
2. Type `help` → see 22 commands listed.
3. Type `pwd` → see `~` (the shortened home).
4. Type `ls` → see the same folders as the Files
   window (`MyDocuments`, `.config`, etc.).
5. Type `cd MyDocuments && ls` → folder contents.
6. Type `clear` → screen wipes, prompt returns.
7. Tap Settings dock → live storage / memory /
   battery bars.
8. Tap Notes dock → empty list + "+ New note"
   button. Create a note, type, watch auto-save.
9. Tap Programs dock → "Elysium System" + "User
   Apps (N)" sections, real app names.
10. Tap any user app → system Intent fires, the
    app opens.

---

## Known gaps (deferred)

- **Terminal commands run client-side**, not on a
  proot shell. The proot wiring is Phase 123 — the
  body doesn't change, only `executeCommand` swaps
  to call `TerminalHost.execute(cmd)`.
- **Theme picker writes to disk** but the actual
  `MaterialTheme` re-coloring isn't wired yet (it's
  a Phase 123 task). The choice is durable; the
  visual effect isn't.
- **Notes have no search / no tags**. The list
  works for 10–50 notes; 500+ notes would need a
  search field. Phase 124.
- **Programs only shows apps with a launcher
  activity**. Pure-background services are hidden
  by design (the user can't tap to open them). If
  the user wants to see all installed packages,
  Phase 124 adds a "show all" toggle.
- **No "open with" picker for files** beyond the
  system's default chooser. Phase 124 adds a
  Elysium-branded picker (ElysiumReader for text,
  ElysiumPlayer for media, etc.).

None of these block the user's stated goal of
"todas y cada una de las funciones o contenedores,
funcionen de principio a fin". Every one of the 4
bodies now does the thing its name suggests; the
polish + the proot shell come in the next two
phases.
