# PHASE 9.14 + 9.15 — CRDT Document Editor (ViewModel + Compose Screen)

Closed: 2026-07-09.

## What landed

### 9.14 — `CrdtDocumentEditorEngine` + `CrdtDocumentEditorViewModel`

- `CrdtDocumentEditorEngine` — pure-logic engine that wraps a
  [`CrdtDocumentSession`](../core/crdt/CrdtDocumentSession.kt)
  and exposes:
  - `state: StateFlow<EditorState>` with `Ready(title, author, body,
    isDirty, lastSavedHlc, nodeId, filePath, lastResult)` —
    the screen reads from this single source of truth.
  - `dispatchSync(intent: EditorIntent)` — applies content-edit
    intents (`SetTitle`, `SetAuthor`, `AppendChar`, `AppendString`,
    `Backspace`) to the underlying CRDT and re-snapshots state.
  - `saveSync()` — persists session + companion file and
    reports `EditorResult.Saved`.
  - `syncSync()` — runs one anti-entropy round via the configured
    `SyncHost`; reports `EditorResult.Synced(N)` or
    `EditorResult.SyncNoPeer`.
  - `SyncHost` interface — the seam real transports (LocalServer,
    SftpServer, Bluetooth) plug into without touching CRDT runtime.
- `CrdtDocumentEditorViewModel` — `@HiltViewModel` + `SavedStateHandle`
  shell. Loads the file referenced by the `{path}` nav arg in `init`,
  binds it to a fresh engine, and forwards UI intents to it. Save and
  Sync are launched on `viewModelScope` + `Dispatchers.IO`.
- Top-level `EditorIntent` / `EditorResult` / `EditorState` —
  the public contract between engine, ViewModel and screen.

### 9.15 — `CrdtDocumentEditorScreen` (Compose)

- New package `com.elysium.vanguard.features.crdteditor`.
- `CrdtDocumentEditorScreen`:
  - `Scaffold` + `TopAppBar` (file name + "CRDT editor · sync-ready"
    sub-label, Back, Save, Sync actions).
  - `MetadataFields` — `BasicTextField` for title + author.
  - `BodyEditor` — `BasicTextField` for the body; append-only
    character model so every keypress = exactly one CRDT insert op
    and every backspace = exactly one delete op. The local
    `BasicTextField` state mirrors the engine's `body` via a
    `remember(body)` key so re-snaps don't clobber the cursor.
  - `StatusRow` — surfaces `isDirty`, `lastSavedHlc`, `nodeId`,
    `bodyChars`, `lastResult` so we can confirm "saved" / "synced 7op"
    in-app.
- Routed as `editor_crdt/{path}` in `MainActivity.kt` (alongside
  `editor_text/` and `editor_md/`).

### 9.14 → 9.15 Bridge bug fix — `CrdtOpLog.parse`

While wiring the screen up end-to-end, the **`sync via SyncHost`
test caught a real bug in `CrdtOpLog.parse`** that has been latent
since Phase 9.9.5:

- Before the fix: `parse()` did `line.trim()` and then
  `parts.size < 3` for `SINS`. If the inserted value was a single
  space character (`" "`), the trim ate the trailing space and the
  line ended up with only 2 tokens, so parse returned `null` and the
  whole companion file was rejected as malformed.
- No existing test caught it because none of the previous CRDT
  collaboration scenarios inserted `SINS " "` strings through a
  save→reload round-trip — they all used the in-memory `replay()`
  path.
- Fix: `parse()` now uses `indexOf(' ')` slicing instead of `split
  (' ')` + `trim`, so single-space values round-trip safely. The
  intent dispatch does NOT need to change; values can already be
  arbitrary strings.

## Test-discovered regressions (2 + 1 fixed in core)

1. **CrdtOpLog.parse dropped single-space values** (caught by the
   new `sync via SyncHost absorbs remote ops and refreshes body`
   test). The companion file existed and was well-formed, but
   `ElysiumSyncFile.parse()` returned `null` because two of Alice's
   ten `SINS` ops had a value of `" "`. Fix in
   `CrdtOpLog.parse`: stop trimming each line; split by
   `indexOf(' ')` instead of `split(' ')`. The companion parser
   already strips `#`-prefixed comment lines, so headers are still
   correctly handled.
2. **`syncViaSyncHost` triggered path A** — same root cause as
   above; once parse was fixed, ops absorbed and the body
   converged to "Alice 1, 2Bob X" on both sides.
3. (Defensive) Added regression test `sync round trip preserves
   body with embedded single spaces` so a future trim()-eats-value
   bug fails fast.

## Quality

- Tests: **622** (+14).
- Failures: **0**.
- `assembleDebug`: **green**, 181 MB APK.

## What this unlocks

- **`CrdtDocumentEditorScreen` is the first user-visible CRDT
  editor in the app.** Opening a `.elysium.word` (or `.sheet`,
  `.deck` with the same engine) routes here. Title, author, body —
  all live-edit, with every change persisted to the op log on
  save.
- **Sync adapter seam** is now visible in the UI. When a real
  `SyncHost` is wired (Phase 9.17 — LocalServer HTTP), the user
  taps "Sync" in the top bar and the body converges with whoever
  holds the companion file.
- **Pre-existing CRDT bug fixed.** The Phase 9.9.5 op-log parser
  was failing silently for any companion file that contained a
  SINS with a single-space value (essentially, any document with
  two consecutive words separated by exactly one space). Every
  earlier Phase 9.10–9.13 test happened to avoid that pattern by
  using either the in-memory `replay()` path or by inserting
  only non-space chars into bodies.

## Deferred to Phase 9.16+

- Wire `LocalServerSyncAdapter` into the engine's `SyncHost` so
  the "Sync" button in the screen can actually pull from a peer
  over HTTP.
- Add an Office import pipeline (`.docx`, `.odt`, `.odp` →
  `.elysium.*`) and surface it from the editor's overflow menu.
- Persist `nodeId` in a `SharedPreferences`-backed
  `NodeIdStore` so the same device keeps the same node id across
  process restarts.

— elysium-autopilot
