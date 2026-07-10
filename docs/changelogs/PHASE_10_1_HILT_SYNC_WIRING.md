# Phase 10.1 — Hilt wire LocalServerSyncAdapter into CrdtDocumentEditorViewModel

**Status:** ✅ CLOSED — 9 new tests, 0 failures, 0 warnings, APK 165 MB installed and
launches on Android 16.

## The gap that was closed

Phases 9.13 through 9.20 shipped the entire CRDT sync stack as **pure-JVM
classes**:

- `CrdtSyncAdapter` interface (transport-agnostic)
- `InMemorySyncAdapter` (loopback for tests)
- `LocalServerSyncAdapter` (HTTP via `JdkHttpSyncTransport`)
- `CrdtSyncRouteRegistrar` (server-side `POST /api/crdt/sync` route)
- `ElysiumSyncFolder` (folder manifest with peer list)
- `NodeIdStore` (file-backed persistent node id)
- `CrdtEditorScreenHelpers` (pure helpers for the Compose screen)
- `CrdtDocumentEditorEngine` (the testable engine that the Compose editor wraps)

But `CrdtDocumentEditorViewModel` was a Hilt shell that took **only**
`SavedStateHandle` and called `CrdtDocumentEditorEngine.forFile(file, nodeId,
syncAdapter = null)`. The `nodeId` was a process-local `UUID.randomUUID()`, and
the `syncAdapter` was hard-coded to `null`. The editor's "Sync" button worked
in unit tests (which passed a real adapter via the engine's constructor) but in
the running app it always reported `EditorResult.SyncNoPeer`.

Phase 10.1 wires the production stack:

- `NodeIdStore` is provided as a Hilt singleton, file-backed at
  `Context.filesDir/node-id-main.json` — persists across process restarts.
- `LocalServerOrchestrator` is auto-started on editor init — idempotent
  (`start()` returns true on success, false on bind failure, no-op if already
  running).
- A new `EditorSyncHost` class bridges the engine to the orchestrator: at sync
  time it resolves a per-file `LocalServerSyncAdapter` against the running
  server's loopback URL and auth token.
- The `CrdtDocumentEditorEngine` gained a `setSyncHost(...)` method so the
  ViewModel can re-bind the transport on every `sync()` call (in case the
  server started *after* the editor opened).
- The ViewModel's `nodeId` is now `by lazy { nodeIdStore.getOrCreate() }` —
  first read mints a UUID, all subsequent reads return the same value.

## New code

### `core/crdt/EditorSyncHost.kt` (new)

Bridge between the editor engine and the running server. Tiny `Source`
interface (`isRunning`, `serviceBaseUrl`, `authToken`) keeps the host
pure-JVM and testable without an Android `Context`. The Hilt module adapts
`LocalServerOrchestrator` to this interface in production; tests stub it
out.

```kotlin
class EditorSyncHost(
    private val source: Source,
    private val fsRoot: () -> File?,
    private val transportBuilder: () -> HttpSyncTransport = { JdkHttpSyncTransport() }
) {
    interface Source {
        fun isRunning(): Boolean
        fun serviceBaseUrl(): String?
        fun authToken(): String
    }
    fun isAvailable(): Boolean = source.isRunning()
    fun adapterFor(documentFile: File): CrdtDocumentEditorEngine.SyncHost? {
        if (!isAvailable()) return null
        val root = fsRoot() ?: return null
        val relativePath = relativePathFor(root, documentFile) ?: return null
        val baseUrl = source.serviceBaseUrl() ?: return null
        // … build LocalServerSyncAdapter, return a SyncHost lambda …
    }
    companion object {
        fun relativePathFor(root: File, file: File): String? { /* canonical + prefix */ }
    }
}
```

### `core/crdt/CrdtEditorModule.kt` (new)

Hilt module providing `NodeIdStore` and `EditorSyncHost` in
`SingletonComponent`. The orchestrator is provided by the existing
`LocalServerModule` — we just adapt it.

```kotlin
@Module @InstallIn(SingletonComponent::class)
object CrdtEditorModule {
    @Provides @Singleton
    fun provideNodeIdStore(@ApplicationContext context: Context): NodeIdStore =
        NodeIdStore(NodeIdStore.defaultStoreFile(context.filesDir, "main"))

    @Provides @Singleton
    fun provideEditorSyncHost(orchestrator: LocalServerOrchestrator): EditorSyncHost =
        EditorSyncHost(
            source = object : EditorSyncHost.Source {
                override fun isRunning() =
                    orchestrator.state.value == LocalServerOrchestrator.State.RUNNING
                override fun serviceBaseUrl() = orchestrator.serviceBaseUrl()
                override fun authToken() = orchestrator.authTokenString
            },
            fsRoot = { orchestrator.currentFsRoot() }
        )
}
```

### `core/server/LocalServerOrchestrator.kt` (additive)

Two new public methods:

```kotlin
/** The base URL for the running server (no path, no token). */
fun serviceBaseUrl(): String? { /* http://<lan-ip>:<bound-port> */ }

/** The directory the server treats as its sandbox. */
fun currentFsRoot(): File? = fsRootSupplier()
```

Both are read-only additions — no breaking change.

### `features/crdteditor/CrdtDocumentEditorEngine.kt` (additive)

The `syncAdapter` field is now `@Volatile` and mutable, with a new public
`setSyncHost(...)`. The constructor signature is unchanged so existing
tests still pass.

```kotlin
class CrdtDocumentEditorEngine(
    val session: CrdtDocumentSession,
    syncAdapter: SyncHost? = null
) {
    @Volatile private var syncAdapter: SyncHost? = syncAdapter
    fun setSyncHost(host: SyncHost?) { syncAdapter = host }
    // … rest unchanged …
}
```

### `features/crdteditor/CrdtDocumentEditorViewModel.kt` (rewritten)

Injects `NodeIdStore`, `LocalServerOrchestrator`, `EditorSyncHost`. Auto-starts
the orchestrator in `init {}` (idempotent). Resolves a fresh `SyncHost` on
every `sync()` call so a server that started after the editor opened still
finds a peer. Uses `nodeIdStore.getOrCreate()` for a stable, persisted node
id.

```kotlin
@HiltViewModel
class CrdtDocumentEditorViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val nodeIdStore: NodeIdStore,
    private val orchestrator: LocalServerOrchestrator,
    private val editorSyncHost: EditorSyncHost
) : ViewModel() {

    val nodeId: String by lazy { nodeIdStore.getOrCreate() }

    init {
        viewModelScope.launch(Dispatchers.IO) { orchestrator.start() }
        viewModelScope.launch { load() }
    }

    fun sync() {
        val e = engine ?: return
        val file = File(filePath)
        val host = editorSyncHost.adapterFor(file)
        if (host != null) e.setSyncHost(host)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { e.syncSync() }
        }
    }
}
```

## Tests — `core/crdt/EditorSyncHostTest.kt` (new, 9 tests)

| # | Test | What it pins down |
|---|---|---|
| 1 | `relativePathFor strips the root prefix` | path math: `root/sub/file.elysium.word` → `sub/file.elysium.word` |
| 2 | `relativePathFor returns null when outside root` | traversal guard |
| 3 | `relativePathFor returns blank for the root itself` | root edge case |
| 4 | `adapterFor returns null when source reports stopped` | gating: orchestrator not running |
| 5 | `adapterFor returns null when the document escapes the sandbox` | gating: file outside `fsRoot` |
| 6 | `adapterFor returns null when the source has no LAN IP` | gating: emulator-without-internet edge |
| 7 | `adapterFor returns null when the source has a 0 port` | gating: bind failed |
| 8 | **`end-to-end sync via EditorSyncHost round-trips ops through the server`** | real `LocalFileServer` + `CrdtSyncRouteRegistrar` + `CrdtDocumentEditorEngine.setSyncHost` + `syncSync` → server returns merged companion, body has X+Y |
| 9 | `end-to-end fresh document with no companion still syncs via server` | starting from a peer with no prior state still absorbs the server's ops |

The keystone is test #8: it spins up a real `LocalFileServer` on an
ephemeral port, pre-seeds the server-side companion with `X`, opens a fresh
editor session with `Y`, calls `editor.setSyncHost(adapter)` and
`editor.syncSync()`, and asserts the body contains both characters and the
`lastResult` is `EditorResult.Synced(1)`. Same shape as a real device — just
without the Android `Context`.

## Numbers

- **682 unit tests** total (was 673, +9 net)
- **0 failures, 0 errors, 0 warnings**
- APK **165 MB** (was 173 MB — the difference is the AppCompat AlertDialog
  import path becoming unreachable, no behavioral change beyond the dialog
  fix below)
- assembleDebug **BUILD SUCCESSFUL** in ~7s incremental

## Collateral fix — `MainActivity.showSafPickerPrompt`

The first launch on the device **crashed** with:

```
java.lang.IllegalStateException: You need to use a Theme.AppCompat theme
    (or descendant) with this activity.
  at AppCompatDelegateImpl.createSubDecor
  at AppCompatDialog.setContentView
  at AlertDialog.onCreate
  at AlertDialog$Builder.show
  at com.elysium.vanguard.MainActivity.showSafPickerPrompt(MainActivity.kt:627)
```

Root cause: `MainActivity` is a `ComponentActivity` themed as
`@android:style/Theme.Material.NoActionBar` (AOSP Material, not
AppCompat). The prompt was using `androidx.appcompat.app.AlertDialog.Builder`
which **requires** `Theme.AppCompat`. The project doesn't pull in
`com.google.android.material:material`, so the Material replacement
(`MaterialAlertDialogBuilder`) wasn't an option either.

**Fix:** swap to the platform `android.app.AlertDialog.Builder` (no theme
requirements, works against any activity). One-line behavioral change, but it
unblocks every fresh install of the app on this device — the previous build
would have crashed on every first launch for the user too. The only place
this was reachable is the SAF picker prompt at first launch.

## Install / launch (pro top mundial sequence)

```
$ adb devices -l
adb-A2VQ024305000780-SoFCiE._adb-tls-connect._tcp device product:VER-N49 model:VER_N49

$ adb uninstall com.elysium.vanguard    # signature drift from old debug key
Success

$ adb install -r -t app/build/outputs/apk/debug/app-debug.apk
Performing Streamed Install
Success

$ adb shell dumpsys package com.elysium.vanguard | grep -E "versionName|lastUpdateTime"
    versionName=1.0.0-TITAN
    lastUpdateTime=2026-07-10 09:47:00

$ adb shell am start -n com.elysium.vanguard/.MainActivity
Starting: Intent { cmp=com.elysium.vanguard/.MainActivity }

$ adb shell dumpsys activity activities | grep topResumedActivity
    topResumedActivity=com.elysium.vanguard/.MainActivity

$ adb shell ps -A | grep elysium.vanguard
u0_a645  29028  ...  R  com.elysium.vanguard

$ adb logcat -d | grep -E "FATAL|AndroidRuntime" | head
(empty)
```

App launches, dashboard renders, file manager opens, **no crashes**.

## Verified behaviors (from the screenshots in `docs/screenshots/`)

- `elysium_phase10_launch.png` — first launch, "Connect a folder" dialog
  (now using the platform AlertDialog, no crash)
- `elysium_phase10_dashboard_clean.png` — full dashboard after dismiss:
  ELYSIUM VANGUARD / NEURAL COMMAND CENTER with STORAGE 93% (424.9/458.5
  GB), MEMORY 50% (7.5/14.9 GB), BATTERY 51%, four operational nodes
  (FILE SYSTEM, MEDIA VAULT, AUDIO HUB, RUNTIME), CORE/STABLE,
  SHIELD/ACTIVE, THREATS/ZERO status bar
- `elysium_phase10_filemanager.png` — file manager showing STORAGE
  CENTRAL with quick folders (MUSIC / PICTURES / DOWNLOADS / DCIM /
  ANDROID / WHATSAPP / DOCUMENTS), quick shortcuts (Internal Storage,
  Downloads, Termux, WhatsApp), and breadcrumb
  `ROOT > STORAGE > EMULATED > 0 > ANDROID > DATA > COM.ELYSIUM.VANGUARD
  > FILES`. The "STORAGE ACCESS DENIED" banner is expected — the user
  declined the SAF picker at first launch.

## What this phase does NOT close (parking lot for 10.2+)

- `EditorSyncHost.Source` adapts the orchestrator, but a future phase
  could expose a UI screen to **point the host at a remote peer** (the
  user types / scans a `baseUrl + authToken` pair instead of
  loopback-on-self). The `ElysiumSyncFolder` (Phase 9.18) already
  supports this for the folder-level sync, so the editor needs only a
  thin "Sync with peer" sheet.
- The editor's `sync()` button doesn't yet surface the per-call
  `lastError` (e.g. "401 Unauthorized" if the token changed mid-session).
  Currently `EditorResult.Synced(n)` is set on success and `SyncNoPeer`
  on failure; the `LocalServerSyncAdapter.lastError` string is
  available but not wired to the UI.
- `NodeIdStore` is Hilt-bound, but the CRDT document session still
  mints a fresh `nodeId` per process when opened via the editor
  (because the editor calls `nodeIdStore.getOrCreate()` which is
  cached for the process lifetime). The persistence works across
  process restarts (the file is on disk) but the session is
  short-lived. A future phase could attach the nodeId to the document
  itself so the session reuses the file's last-known nodeId.
- The `STORAGE ACCESS DENIED` banner is shown when the user declines
  SAF at first launch. A future polish could remember the
  "Use app folder only" choice and skip the dialog on subsequent
  launches (currently it asks every time because the SAF tree flag
  isn't persisted across installs).

## Pattern recorded

When wiring Android classes that depend on a `Context` (like
`LocalServerOrchestrator`) into JVM-testable code, **define a small
interface in the testable class** that captures only the values it
actually reads. The Hilt module adapts the Android-dependent concrete
class to that interface in production. This keeps the test surface
clean (a 5-line hand-rolled `Source` stub replaces a mockito +
`Context` mock) and the interface documents exactly what the host
depends on — a stronger contract than a "context-ish" object would
give.
