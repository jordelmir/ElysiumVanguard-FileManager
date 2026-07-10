# PHASE 9.18 — `ElysiumSyncFolder` (directory-level sync manifest)

Closed: 2026-07-10.

## What landed

### `ElysiumSyncFolder` (8 tests)

- A user-authorable folder-level sync manifest that lives at
  `<dir>/.elysium-sync-folder.json`. Drop one into any
  directory and every `.elysium.*` file in that folder (whose
  filename matches the include patterns) syncs to every peer
  listed in the manifest.
- Manifest format (JSON, hand-writable):

  ```json
  {
    "patterns": ["*.elysium.word", "*.elysium.sheet"],
    "peers": [
      { "name": "Laptop", "baseUrl": "http://192.168.1.20:8765",
        "authToken": "abc123" }
    ],
    "lastUpdated": "2026-07-09T23:34:56Z"
  }
  ```

- `ElysiumSyncFolder.lookup(directory)` reads an existing
  manifest; returns `null` if none.
- `ElysiumSyncFolder.create(directory, patterns, peers)` writes
  a fresh manifest. Defaults `patterns` to `["*.elysium.word"]`
  when callers pass an empty list (better out-of-the-box than
  "sync nothing").
- `ElysiumSyncFolder.listDocuments()` — pure globs (`*`, `?`)
  against the manifest's patterns; no extra regex lib.
- `ElysiumSyncFolder.syncAll(sessionFactory, transportBuilder,
  documentPathFor)` walks every matching document, opens it
  via the supplied factory, runs a sync round against every
  peer via [LocalServerSyncAdapter], saves the doc back,
  accumulates total ops absorbed.
- `ElysiumSyncFolder.matchGlob(name, pattern)` — internal but
  visible to tests; supports `*` and `?` with proper regex
  escaping (so `.` doesn't accidentally mean "any char").

### Error tolerance

- `fromJsonText` filters out malformed `peers` entries
  (missing `name` / `baseUrl` / `authToken`) instead of
  throwing — a partial manifest should still load whatever
  peers are well-formed.
- Malformed JSON manifests return `null` from `lookup` (the
  file may be hand-edited; we don't want to crash the editor
  because of a typo).
- `syncAll` wraps each per-(doc, peer) call in `runCatching`
  so one bad peer doesn't abort the whole round.

## Tests (8, all green)

- `lookup returns null when no manifest exists`
- `create writes a manifest file and lookup parses it back`
- `default pattern falls back to elysium word when caller passes empty list`
- `matchGlob handles star and question-mark wildcards`
- `listDocuments returns only matching files`
- `syncAll invokes adapter for each matching document against each peer`
  (uses a fake `HttpSyncTransport` recording every POST URL)
- `lookup falls back to null on malformed manifest`
- `fromJsonText tolerates peers list missing fields`

## Quality

- Tests: **662** (+8).
- Failures: **0**.
- `assembleDebug`: green, 173 MB APK.

## What this unlocks

"Drop a `.elysium-sync-folder.json` into a directory and the
folder joins the CRDT mesh" becomes a one-tap operation.
This is the manual-config pre-stage of Phase 9.19 (persistent
node id, so the same device keeps the same `nodeId` across
launches) and Phase 9.20 (UI gesture in the editor).

— elysium-autopilot
