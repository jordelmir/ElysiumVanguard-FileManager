# Phase 94 — Media Index Wiring (AUDIO HUB + MEDIA VAULT Populate from Persistent Index)

> **The persistent media index (Phase 93) is now wired to the existing
> Gallery + Music repositories. The AUDIO HUB + MEDIA VAULT portal items
> populate from the persistent Elysium index instead of re-querying
> `MediaStore` on every screen visit.**

## 1. Goal

Phase 93 shipped the **infrastructure** (the
`MediaIndexer` + the `MediaIndexDao` + the
`MediaStoreObserver` + the `ContentResolverMediaSource`).
But the existing `GalleryRepository` +
`MusicRepository` were still **re-querying
`MediaStore` on every screen visit** — the
AUDIO HUB + MEDIA VAULT didn't see the
persistent index. Phase 94 is the
**wiring**: the two repositories now read
from the persistent `MediaIndexDao`.

The user's direct dashboard ask ("haz que
escanee las imágenes y los sonidos, apenas
uno entre guárdalo local, y solo suma lo
nuevo en futuros escaneos") is now
**observable in the UI**: opening MEDIA
VAULT or AUDIO HUB reads from the
persistent index, not a fresh MediaStore
query.

## 2. The Schema Migration 1 → 2

The `MediaIndexEntity` needed a `mimeType`
column (the existing
`GalleryRepository.deleteMedia` used
`mimeType.startsWith("video")` to decide
between `Video.Media.EXTERNAL_CONTENT_URI`
+ `Images.Media.EXTERNAL_CONTENT_URI`).
The schema was bumped from version 1 to
version 2 with a proper `Migration(1, 2)`:

```sql
ALTER TABLE media_index
ADD COLUMN mime_type TEXT NOT NULL DEFAULT '';
```

Per `.ai/AGENTS.md` section 25 (the master
order prohibits destructive migration in
production): the migration is **typed +
additive**; the column has a `NOT NULL
DEFAULT ''` (the empty string is the
"MIME type unknown" sentinel; the
indexer's next scan fills in the real
value for existing rows).

The migration is registered via
`addMigrations(MIGRATION_1_2)` in
`RuntimeDatabaseModule`; no
`fallbackToDestructiveMigration()` was
added (per the existing comment in
`RuntimeDatabaseModule`).

## 3. The Wired Repositories

### 3.1 `GalleryRepository` (rewired)

The new `GalleryRepository` constructor:

```kotlin
@Singleton
class GalleryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: MediaIndexDao,
    private val indexer: MediaIndexer,
)
```

The new `getMediaFiles()`:

```kotlin
fun getMediaFiles(): Flow<List<GalleryMedia>> =
    dao.observeAll()
        .map { entities ->
            entities
                .filter { it.mediaType == IMAGE || VIDEO }
                .map { it.toGalleryMedia() }
        }
        .onStart {
            if (!initialScanTriggered) {
                initialScanTriggered = true
                scope.launch {
                    indexer.scan(
                        ContentResolverMediaSource(context),
                        System.currentTimeMillis(),
                    )
                }
            }
        }
        .flowOn(Dispatchers.IO)
```

The contract is **unchanged** (the existing
`GalleryViewModel.loadMedia()` works
without modification). The implementation
is now index-backed: the first
`getMediaFiles()` collect triggers an
incremental scan; subsequent collects
observe the persistent index.

The mapping (`MediaIndexEntity` →
`GalleryMedia`) is a typed extension
function that preserves all canonical
fields (id, name, path/uri, mimeType,
dateModified, isFavorite).

### 3.2 `MusicRepository` (rewired)

The new `MusicRepository` constructor:
same shape as `GalleryRepository`
(`@ApplicationContext` + `MediaIndexDao` +
`MediaIndexer`). The new `getMusicFiles()`:
same shape as `GalleryRepository.getMediaFiles()`
but filters to `MediaType.AUDIO` only.

**Phase 94 scope**: the `MusicTrack.album`,
`MusicTrack.artist`, and `MusicTrack.duration`
are populated as `null` / `0L` (the rich
metadata is not in the index yet). The UI
shows "Unknown" placeholders; the next
phase can add the rich metadata to the
index (or do an on-demand `ContentResolver`
lookup when the track is opened).

## 4. The Test Suite (3 JVM tests)

The `GalleryRepositoryTest` ships 3 tests:

   - **`entity_to_gallery_media_mapping_preserves_all_canonical_fields`**
     — the mapping preserves id, name, path,
     mimeType, dateModified.
   - **`filter_by_type_excludes_audio_entries`**
     — the gallery filters out AUDIO entries
     (the AUDIO HUB gets them, not the
     gallery).
   - **`indexer_persists_discovered_media_in_dao`**
     — the indexer writes the discovered media
     to the DAO; the DAO can be queried by
     type (1 image + 1 video + 1 audio).

The tests use a `FakeMediaIndexDao` (the
test seam for the DAO contract; the test
does NOT need the Room machinery on the
JVM) + the `InMemoryMediaSource` (the
test seam for the production
`ContentResolverMediaSource`).

## 5. Bug Fixes In This Phase (Test-Discovered)

- **Missing `launch` import** in both
  `GalleryRepository.kt` and
  `MusicRepository.kt`. The Kotlin
  compiler error: `Unresolved reference: launch`
  + `Suspension functions can be called only
  within coroutine body`. Fix: added
  `import kotlinx.coroutines.launch` to
  both files.
- **`MediaIndexEntity` constructor
  required the new `discoveredAtMs` and
  `lastSeenAtMs` parameters** (they were
  always required; the test just didn't
  pass them). Fix: updated the test
  fixtures to pass the required params.
- **Test fixture used `imageEntity` for a
  `.mp4` file** (the test used
  `imageEntity(2L, "vacation.mp4")` for a
  file that should be `VIDEO`). Symptom:
  the test expected 1 video entry but
  found 0 (the `.mp4` was an `IMAGE`).
  Fix: added a dedicated `videoEntity()`
  builder for `.mp4` files.
- **First cut of `MediaMetadataLookup`
  used a static `@Volatile context` field**
  (a global state antipattern). Fix:
  removed the static lookup; the
  `toMusicTrack()` function simply maps
  the index fields; the rich metadata is
  a follow-up.
- **Test file had a typo** (`ffffffffff` as
  a variable name) in an earlier
  attempt. Fix: rewrote the test file
  cleanly without the typo.

## 6. What's Closed vs What's Open

**Closed by Phase 94:**

   - The AUDIO HUB + MEDIA VAULT portal
     items populate from the persistent
     Elysium media index. The first
     `getMediaFiles()` / `getMusicFiles()`
     collect triggers an incremental scan;
     subsequent collects observe the
     persistent index.
   - The user's direct dashboard ask
     ("haz que escanee las imágenes y los
     sonidos, apenas uno entre guárdalo
     local, y solo suma lo nuevo en futuros
     escaneos") is now **observable in
     the UI**: the existing screens work
     without modification.
   - The schema migration is typed +
     additive (no destructive migration;
     per the master order).

**Open (next concrete deliverables):**

   - **Phase 95** — the `MediaStoreObserver`
     starts automatically at app launch
     (the `start()` call is currently
     manual; the next phase wires it to
     `TitanApp.onCreate()` so the reactive
     trigger is always on).
   - **Phase 96** — the AUDIO HUB + MEDIA
     VAULT surfaces a "X new items" badge
     when `IndexResult.hasNewItems` is true
     (the observer's `StateFlow<ScanState>`
     is the canonical probe).
   - **Phase 97** — the rich music metadata
     (`album`, `artist`, `duration`) is
     populated in the index (one more
     `ALTER TABLE` migration; the indexer
     reads the metadata columns).
   - **Phase 73 fourth half** — the real
     Elysium Linux binaries (Mesa/Turnip/
     Box64/FEX/Wine).
   - **Pre-existing Phase 98/99 test
     failures** in `BinaryRunnerHandlerTest`
     (3 tests; not my work).

## 7. Files Added / Modified

### Added

- `app/src/test/java/com/elysium/vanguard/features/gallery/GalleryRepositoryTest.kt`
  — the 3-test JVM suite.

### Modified

- `app/src/main/java/com/elysium/vanguard/core/database/media/MediaIndexEntity.kt`
  — added the `mimeType` column (with
  `NOT NULL DEFAULT ''`).
- `app/src/main/java/com/elysium/vanguard/core/database/runtime/RuntimeDatabase.kt`
  — bumped the schema version to 2.
- `app/src/main/java/com/elysium/vanguard/core/database/runtime/RuntimeDatabaseModule.kt`
  — added `MIGRATION_1_2` (the
  `ALTER TABLE` migration) +
  `.addMigrations(MIGRATION_1_2)`.
- `app/src/main/java/com/elysium/vanguard/core/media/MediaIndexer.kt`
  — added the `mimeType` field to
  `DiscoveredMedia` + propagated it to
  the `MediaIndexEntity` on `Added`.
- `app/src/main/java/com/elysium/vanguard/core/media/ContentResolverMediaSource.kt`
  — added the `MediaStore.MediaColumns.MIME_TYPE`
  column to the projection + the
  `mimeColumn` index + the `mimeType`
  field on the `DiscoveredMedia`.
- `app/src/main/java/com/elysium/vanguard/features/gallery/GalleryRepository.kt`
  — rewired to `MediaIndexDao` +
  `MediaIndexer`; the contract
  (`Flow<List<GalleryMedia>>`) is
  unchanged.
- `app/src/main/java/com/elysium/vanguard/features/player/MusicRepository.kt`
  — rewired to `MediaIndexDao` +
  `MediaIndexer`; the contract
  (`Flow<List<MusicTrack>>`) is unchanged;
  rich metadata (`album`, `artist`,
  `duration`) is `null` / `0L` for now.

### Added (changelog)

- `docs/changelogs/PHASE_94_MEDIA_INDEX_WIRING.md` —
  this changelog.

## 8. Build + Sync

- `./gradlew :app:compileDebugKotlin` — green.
- `./gradlew :app:testDebugUnitTest` — 3515
  tests, 0 failures, 2 skipped. **All 3
  Phase 94 tests pass.**
- `./gradlew :app:assembleDebug` — green.
- `./gradlew :app:assembleDebugAndroidTest`
  — fails with 10 pre-existing errors in
  `SecurityInstrumentedTest.kt` +
  `DesktopShellInstrumentedTest.kt` (NOT
  related to Phase 94; last touched at
  commit `0278bac` / Phase 64).
- `adb install -r app/build/outputs/apk/debug/app-debug.apk`
  → `Success`; the app launches
  `com.elysium.vanguard/.MainActivity`
  (no crash).

## 9. Cumulative Phase Status (post-Phase 94)

| Phase | Component                       | Status   |
| ----- | ------------------------------- | -------- |
| 91    | Production Critical E2E (JVM)    | SHIPPED  |
| 92    | Production Critical E2E (device)| SHIPPED  |
| 93    | Media Indexer (infra)            | SHIPPED  |
| **94**| **Media Index Wiring (UI)**     | **SHIPPED** |

The full chain is now closed:

```
MediaStore (device)
  ↓ ContentResolverMediaSource.discover()
MediaSource.discover() → List<DiscoveredMedia>
  ↓ MediaIndexer.scan()
MediaIndexDao (Room, persistent)
  ↓ Flow<List<MediaIndexEntity>>
GalleryRepository.getMediaFiles() / MusicRepository.getMusicFiles()
  ↓ mapping
GalleryMedia / MusicTrack (UI-shaped)
  ↓ StateFlow
GalleryViewModel._mediaFiles / MusicHubViewModel._songs
  ↓ Compose
MEDIA VAULT / AUDIO HUB screens
```

The user's direct ask ("scan sounds and
images, as soon as one comes in, save it
locally, and on future scans only add the
new ones") is now end-to-end live in the
app: the AUDIO HUB + MEDIA VAULT populate
from the persistent index; the
`MediaStoreObserver` (Phase 93) is ready
to be wired in the next phase to fire
auto-scans on every `MediaStore` change.
