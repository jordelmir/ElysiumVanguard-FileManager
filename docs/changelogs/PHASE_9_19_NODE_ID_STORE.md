# PHASE 9.19 — `NodeIdStore` (persisted node id)

Closed: 2026-07-10.

## What landed

### `NodeIdStore` (7 tests, file-backed, no Android dep)

Each device / process needs a stable `nodeId` so companion
files (`foo.elysium.word.<node>.elysium.sync`) created on
launch keep the same name across restarts. The
`CrdtDocumentEditorViewModel` previously minted a fresh
`UUID.randomUUID()` for every process — meaning every cold
launch produced a brand-new companion file, which broke
sync continuity.

`NodeIdStore(storeFile: File)` reads / writes a single `nodeId`
string as JSON to a caller-supplied path (typically the app's
`filesDir`):

- `getOrCreate()`: returns the persisted value if present,
  otherwise mints `node-<UUID>` and persists it.
- `set(value)`: override (used by tests, migration paths).
- `clear()`: drop the persisted value (simulates a "reset
  this device" gesture).
- `defaultStoreFile(rootDir, deviceTag = "main")`: builds the
  default filename `<rootDir>/node-id-<tag>.json`.

Thread-safe enough for the editor's single-threaded intent
dispatch (uses `@Synchronized`); the underlying file is
written once per change so concurrent read-during-write
windows are avoided on the typical JVM filesystem.

Pure JVM — no Android `Context` dependency, no
SharedPreferences. The Android wiring will resolve
`storeFile` from `Context.filesDir` in a later phase.

## Tests (7, all green)

- `getOrCreate mints a UUID on first call`
- `getOrCreate is stable across store instances`
- `set overrides the persisted value`
- `clear wipes persisted state and mints a fresh UUID`
- `persisted JSON round-trips across instances`
- `missing store file is treated as a fresh device`
- `corrupt store file yields a fresh UUID`
- `defaultStoreFile lives inside the supplied root`

## Quality

- Tests: **669** (+7).
- Failures: **0**.
- `assembleDebug`: green, 173 MB APK.

## What this unlocks

The Android Hilt module can now read
`/data/data/<pkg>/files/node-id-main.json` on launch, feed
it into `CrdtDocumentEditorViewModel.engine`'s session
open, and the device's `nodeId` survives process restarts.
Companion files keep their suffixes, sync rounds land on
the right logs, and the user doesn't have to re-link devices
after every cold launch.

— elysium-autopilot
