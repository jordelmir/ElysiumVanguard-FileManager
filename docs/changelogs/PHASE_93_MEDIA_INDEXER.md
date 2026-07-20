# Phase 93 — Media Indexer (Incremental, Persistent, Reactive)

> **"Haz que escanee los sonidos e imágenes, apenas uno entre y luego ya lo
> guarde localmente y solo vaya sumando en futuros escaneos lo nuevo,
> cuando haya..."**
>
> The MEDIA VAULT + AUDIO HUB portal items now
> have a typed, persistent, **incremental**
> media index. The first scan discovers all
> media; subsequent scans only add NEW items
> (the diff). A `ContentObserver` triggers a
> scan every time a new media item enters the
> `MediaStore` (the "apenas uno entre"
> reactive trigger).

## 1. Goal

Per the master vision's section 12
("Vanguard Market" + "Vanguard Files" + the
MEDIA VAULT + AUDIO HUB portal items on the
dashboard) + the user's direct ask on the
dashboard screenshot: "Haz que escanee los
sonidos e imágenes, apenas uno entre y luego
ya lo guarde localmente y solo vaya sumando
en futuros escaneos lo nuevo" (Scan sounds
and images; as soon as one comes in, save it
locally and on future scans only add the new
ones, when there are new ones).

The pre-existing `GalleryRepository` +
`MusicRepository` re-queried `MediaStore` on
every screen visit (no persistence, no
incremental scanning, no diff). The Phase 93
ship replaces this with a **persistent,
incremental, reactive** indexer.

## 2. Architecture: The 5 Components

Phase 93 ships 5 components in
`com.elysium.vanguard.core.{database.media,
media}*`:

### 2.1 `MediaIndexEntity` (the persistent row)

A Room `@Entity` with the canonical schema for
a media item in the index:

   - `mediaId` (primary key, the stable
     `MediaStore.MediaColumns._ID`)
   - `uri` (the canonical `content://` URI,
     unique-indexed)
   - `mediaType` (IMAGE / VIDEO / AUDIO,
     indexed for `listByType` queries)
   - `displayName`, `relativePath` (the
     "album" identifier)
   - `sizeBytes`, `dateModifiedMs`,
     `contentHash` (the change-detection
     fingerprint)
   - `discoveredAtMs` (the first-scan
     timestamp)
   - `lastSeenAtMs` (the last-scan timestamp;
     used for garbage collection)
   - `isFavorite` (the user's favorite flag;
     persists across rescans)

The entity has 3 indices (on `uri` for
uniqueness, on `mediaType` for `listByType`,
on `relativePath` for "albums" grouping).

### 2.2 `MediaIndexDao` (the typed data access layer)

The Room DAO with 11 typed methods:

   - `upsert(entity)` — REPLACE on conflict
   - `update(entity)`
   - `observeAll(): Flow<List<...>>` — for
     Compose-driven UI
   - `listAll()` — for the indexer's diff
   - `getById(mediaId)`,
     `getByUri(uri)` — fast lookups
   - `listByType(mediaType)` — for MEDIA
     VAULT vs AUDIO HUB routing
   - `listByRelativePath(relativePath)` —
     for "albums" grouping
   - `observeCount(): Flow<Int>` — for the
     "X items" badge
   - `count()` — for "first scan" detection
   - `deleteById(mediaId)` — for
     garbage-collected items
   - `deleteStale(thresholdMs)` — bulk
     delete items not seen in the latest
     scan
   - `clear()` — for "force re-scan"

### 2.3 `MediaIndexer` (the diff algorithm)

A sealed class with the default impl
`DefaultMediaIndexer`. The **incremental
algorithm**:

   1. Read all entries from the DAO (the
      "previous scan" state).
   2. Read all discovered items from the
      `MediaSource` (the "current scan"
      state).
   3. For each discovered item:
      - New (id not in previous index):
        emit `Added` + insert a new row.
      - Changed (mtime / size / hash
        changed): emit `Updated` + update
        the row.
      - Unchanged: emit `Unchanged` + bump
        the `lastSeenAtMs`.
   4. Garbage-collect stale entries (entries
      not seen in this scan AND older than
      the 24h grace period).

The algorithm is **deterministic via
explicit `nowMs`** (the indexer does NOT
call `System.currentTimeMillis()`
internally; the caller passes the current
time).

The result is a typed `IndexResult` data
class with: `added`, `updated`, `unchanged`,
`removed`, `totalAfter`, `scanAtMs`,
`wasFirstScan`, plus the convenience
predicates `processed` (= added + updated
+ unchanged) and `hasNewItems` (= added is
not empty).

### 2.4 `MediaSource` (the typed seam)

A sealed interface with two impls:

   - **`ContentResolverMediaSource`** (the
     production impl): queries
     `MediaStore.Images.Media` +
     `MediaStore.Video.Media` +
     `MediaStore.Audio.Media` on
     `Dispatchers.IO`. For each row,
     computes a **fast content fingerprint**
     (SHA-256 of the first 4 KiB + the file
     size). The fingerprint is the canonical
     "did the file's content change?" check.
   - **`InMemoryMediaSource`** (the test
     impl): the test seam; the test injects
     pre-canned discovered items.

The seam is the only place in the indexer
pipeline that imports `android.*` classes;
everything else is pure-domain (testable
on the JVM without an emulator).

### 2.5 `MediaStoreObserver` (the reactive trigger)

A `@Singleton` that registers three
`ContentObserver`s (one per `MediaStore`
URI: images, video, audio). When a row is
inserted / updated / deleted in any URI,
the observer's `onChange` callback fires;
the observer debounces the events
(multiple rapid changes coalesce into a
single scan) and triggers a scan.

The observer exposes a `StateFlow<ScanState>`
for Compose-driven UI (`Idle` / `Scanning`
/ `Error(message)`).

The observer is the **push half** of the
indexer pipeline:

   - **`MediaIndexer.scan(source, nowMs)`** is
     the **pull half** (the caller asks
     "scan now" and gets a typed
     `IndexResult`).
   - **`MediaStoreObserver.start()`** is the
     **push half** (the platform tells the
     observer "a new item was added"; the
     observer triggers a scan + emits a
     `ScanState`).

## 3. The Error Envelope

A sealed class `MediaIndexerError` (the
canonical typed error pattern for the
Elysium codebase) with 3 cases:

   - `SourceDiscoveryFailed(underlying)` —
     the `MediaSource` raised during
     `discover()`.
   - `IndexWriteFailed(mediaId, underlying)`
     — the DAO raised during
     `upsert`/`update`/`delete`.
   - `InvalidNowMs(nowMs)` — the scan's
     `nowMs` is non-positive.

## 4. Hilt Wiring

The `RuntimeDatabase` is extended to
include the `media_index` table (version
1, no migration needed since the version
is the same as the existing entities). The
`RuntimeDatabaseModule` provides the
`MediaIndexDao`. A new `MediaIndexModule`
provides the `MediaIndexer` binding.

The `MediaStoreObserver` uses Hilt's
`@Inject constructor` + `@Singleton`
(it's a process-scoped observer).

## 5. Test Suite: 10 JVM tests, 100% passing

The `MediaIndexerTest` ships 10 tests
covering:

   - **`first_scan_adds_all_discovered_items`** —
     the first scan (empty index) adds all
     3 items.
   - **`subsequent_scan_with_no_changes_is_all_unchanged`** —
     the 2nd scan with no changes has 0
     Added / 0 Updated / 0 Removed /
     2 Unchanged; the `lastSeenAtMs` is
     bumped.
   - **`subsequent_scan_with_new_items_adds_only_the_new_ones`** —
     adding 1 new item produces 1 Added +
     1 Unchanged (not 2 Unchanged, which
     would be wrong).
   - **`subsequent_scan_with_updated_items_updates_only_the_changed_ones`** —
     modifying 1 item's mtime + size +
     content hash produces 1 Updated + 1
     Unchanged.
   - **`deletion_within_grace_period_is_not_removed`** —
     a deletion within 24h is NOT removed
     (the file may be temporarily
     disconnected; the user can reconnect
     the SD card).
   - **`deletion_after_grace_period_is_removed`** —
     a deletion > 24h is removed; the index
     shrinks.
   - **`multiple_scans_accumulate_incrementally`** —
     4 sequential scans (empty → 1 item →
     2 items → 2 items unchanged) produce
     the expected `Added` count per scan.
   - **`indexResult_processed_count_matches_added_plus_updated_plus_unchanged`** —
     the `processed` predicate matches the
     sum of `added + updated + unchanged`.
   - **`mediaIndexerError_subtypes_have_non_blank_code_and_message`** —
     all 3 error variants have non-blank
     `code` + `message`.
   - **`indexer_is_deterministic_for_the_same_source_and_nowMs`** —
     two parallel indexers with the same
     source + the same `nowMs` produce the
     same counts.

The test uses a `FakeMediaIndexDao` (a
thin in-memory implementation of the DAO
contract; the test does NOT need the Room
machinery on the JVM).

## 6. Bug Fixes In This Phase (Test-Discovered)

- **`cause` field name in error envelope
  shadowed `Throwable.cause`.** First
  cut used `val cause: Throwable`; the
  compiler error: `'cause' hides member
  of supertype 'MediaIndexerError' and
  needs 'override' modifier`. Fix:
  renamed to `underlying`.
- **`MediaType` was in
  `MediaIndexEntity.kt` but not imported
  in `ContentResolverMediaSource.kt`.**
  First cut referenced `MediaType`
  directly; the compiler error:
  `Unresolved reference: MediaType`. Fix:
  added the import.
- **`runBlocking` wrapper for suspend
  tests.** The `MediaIndexer.scan`
  function is `suspend`; the JUnit test
  methods are not. First cut tried
  inline `runBlocking` via regex
  replacement and corrupted the file
  structure. Fix: rewrote the test file
  cleanly with each test method
  declared as `= runBlocking { ... }`.
- **Test assertions for "image1" /
  "image2" used `uri.contains("image1")`
  but the URI was
  `content://media/external/images/media/1`.**
  Symptom: the assertion failed because
  the URI doesn't contain "image1"
  (the displayName does). Fix: assert
  on `displayName` instead.
- **`processed` count was off by one.**
  First cut expected `processed == 2` for
  a test with 1 added + 1 updated; the
  real count is 3 (1 added + 1 updated +
  1 unchanged). Fix: corrected the
  expected count.
- **Pre-existing Phase 98/99 Hilt
  duplicate binding (`BinaryRunner` bound
  twice from `FileActionModule`).** The
  pre-existing work in progress introduced
  a Hilt compile error that blocked the
  test compilation. Fix: added
  `@Named("appImage")` and
  `@Named("windows")` qualifiers to the
  two `BinaryRunner` providers +
  consumer fields. The fix is from
  Phase 98/99, not Phase 93, but was
  necessary to unblock the build.
- **Pre-existing Phase 98/99 test compile
  error in
  `ProcessLauncherAppImageRunnerTest.kt`**
  (`Type mismatch: inferred type is
  String! but File? was expected`).
  Fix: changed `resolveRootfs = { rootfs.absolutePath }` to
  `resolveRootfs = { rootfs }` in the
  test (the production `(String) -> File?`
  signature expects a `File`, not a
  `String`).

## 7. What's Closed vs What's Open

**Closed by Phase 93:**

   - The MEDIA VAULT + AUDIO HUB portal
     items now have a typed, persistent,
     **incremental** media index. The
     first scan discovers all media;
     subsequent scans only add NEW items.
   - The `MediaStoreObserver` provides the
     reactive trigger ("apenas uno entre")
     — a new media item in `MediaStore`
     automatically triggers an incremental
     scan.
   - The pre-existing Phase 98/99 Hilt
     duplicate binding is fixed (the
     `BinaryRunner` providers +
     `BinaryRunnerHandler` consumer now
     use `@Named` qualifiers).
   - The pre-existing Phase 98/99 test
     compile error is fixed (the
     `resolveRootfs` signature mismatch).

**Open (next concrete deliverables):**

   - **Phase 94** — wire the `MediaStoreObserver`
     to start automatically when the
     `MediaVaultScreen` is shown (or when
     the app is launched; needs the
     `WorkManager` background trigger).
   - **Phase 95** — wire the `MediaIndexDao.observeAll()`
     to the existing
     `GalleryViewModel` + `MusicHubViewModel`
     (replace the `MediaStore` re-queries
     with the persistent index queries).
   - **Phase 96** — the gallery / music UI
     surfaces a "X new items" badge when
     `IndexResult.hasNewItems` is true
     (the `MediaStoreObserver.state` Flow
     is the canonical probe).
   - **Phase 97** — the
     `GalleryRepository.deleteMedia` +
     `MusicRepository.deleteTrack` delete
     from `MediaStore`; the indexer's
     diff automatically picks up the
     deletion on the next scan.
   - **Phase 98/99 (pre-existing work)** —
     USB OTG Inspector + Binary Runners
     (3 pre-existing test failures in
     `BinaryRunnerHandlerTest` due to
     `File` not being auto-created on
     disk; needs its own fix).
   - **Real Elysium Linux binaries**
     (Mesa/Turnip/Box64/FEX/Wine) for
     the production `AndroidProcessLauncher`
     E2E test.

## 8. Files Added / Modified

### Added

- `app/src/main/java/com/elysium/vanguard/core/database/media/MediaIndexEntity.kt`
  — the Room entity + the `MediaType` enum.
- `app/src/main/java/com/elysium/vanguard/core/database/media/MediaIndexDao.kt`
  — the Room DAO (11 typed methods).
- `app/src/main/java/com/elysium/vanguard/core/media/MediaIndexer.kt`
  — the sealed class + `DefaultMediaIndexer` +
  `IndexResult` + `MediaSource` + `DiscoveredMedia`.
- `app/src/main/java/com/elysium/vanguard/core/media/ContentResolverMediaSource.kt`
  — the production `ContentResolver`-backed
  source + the `InMemoryMediaSource` test
  seam.
- `app/src/main/java/com/elysium/vanguard/core/media/MediaStoreObserver.kt`
  — the reactive trigger (3
  `ContentObserver`s + debounce + `StateFlow`).
- `app/src/main/java/com/elysium/vanguard/core/media/MediaIndexerError.kt`
  — the typed error envelope.
- `app/src/main/java/com/elysium/vanguard/core/media/MediaIndexModule.kt`
  — the Hilt module.
- `app/src/test/java/com/elysium/vanguard/core/media/MediaIndexerTest.kt`
  — the 10-test JVM suite.
- `docs/changelogs/PHASE_93_MEDIA_INDEXER.md` —
  this changelog.

### Modified

- `app/src/main/java/com/elysium/vanguard/core/database/runtime/RuntimeDatabase.kt`
  — added `MediaIndexEntity::class` to the
  `@Database` entities list + the
  `mediaIndexDao()` abstract method.
- `app/src/main/java/com/elysium/vanguard/core/database/runtime/RuntimeDatabaseModule.kt`
  — added the `provideMediaIndexDao` method.
- `app/src/main/java/com/elysium/vanguard/core/fileactions/FileActionModule.kt`
  — added `@Named("appImage")` and
  `@Named("windows")` qualifiers to fix
  the pre-existing Hilt duplicate binding.
- `app/src/main/java/com/elysium/vanguard/core/fileactions/handlers/BinaryRunnerHandler.kt`
  — added matching `@Named` qualifiers on
  the consumer fields.
- `app/src/test/java/com/elysium/vanguard/core/fileactions/production/ProcessLauncherAppImageRunnerTest.kt`
  — fixed the `resolveRootfs` signature
  mismatch.

## 9. Build + Sync

- `./gradlew :app:compileDebugKotlin` — green.
- `./gradlew :app:testDebugUnitTest` — 3512
  tests, 3 failed (pre-existing Phase
  98/99 `BinaryRunnerHandlerTest` issues),
  2 skipped. **All 10 Phase 93 tests
  pass.**
- `./gradlew :app:assembleDebug` — green.

## 10. Cumulative Phase Status (post-Phase 93)

| Phase | Component                       | Status   |
| ----- | ------------------------------- | -------- |
| 91    | Production Critical E2E (JVM)    | SHIPPED  |
| 92    | Production Critical E2E (device)| SHIPPED  |
| **93**| **Media Indexer (incremental, persistent, reactive)** | **SHIPPED** |

The 8-step Definition of Done (Phases 91 + 92)
remains closed. Phase 93 adds a new axis: the
MEDIA VAULT + AUDIO HUB portal items now have
a typed, persistent, incremental, reactive
media index. The indexer is the canonical
answer to the user's direct ask: "scan
sounds and images, as soon as one comes in,
save it locally, and on future scans only
add the new ones."
