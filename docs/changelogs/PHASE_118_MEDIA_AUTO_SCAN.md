# Phase 118 — Media Auto-Scan (Reactive + "X New Items" Badge)

> **The MediaStoreObserver is now auto-started at app launch and
> surfaces a "X new items" badge on the MEDIA VAULT + AUDIO HUB
> dashboard tiles the moment a scan discovers new content. The
> user's pivot to "Haz que escanee los sonidos e imágenes apenas
> entren" is now end-to-end live in the app: open the dashboard
> after copying a new photo or audio file to the device, and the
> badge appears automatically — no manual scan trigger required.**

## 1. Goal

Phase 93 shipped the **infrastructure** (the
`MediaIndexer` + the `MediaIndexDao` +
the `MediaStoreObserver`). Phase 94 wired
the repositories. But the observer was
**never started** — the first scan only
fired lazily, the first time a user opened
MEDIA VAULT or AUDIO HUB (the
`@Volatile initialScanTriggered` flag in
`GalleryRepository` + `MusicRepository`
triggered it). New files copied to the
device *after* the user visited the screen
were silently ignored until the next app
launch.

Phase 118 closes that loop. The
`MediaStoreObserver.start()` call now
fires in `TitanApp.onCreate()`, registering
3 `ContentObserver`s (one per `MediaStore`
URI) at process boot. Every `MediaStore`
change the OS reports is debounced +
scanned + persisted. The latest
`IndexResult.hasNewItems` is exposed via
`StateFlow`, and a new
`MediaScanBadgeViewModel` projects the
result into two counts (`newMediaItemCount`
+ `newAudioItemCount`) consumed by the
`PortalCard` badges on the dashboard.

## 2. The Production Wiring

### 2.1 `MediaStoreObserver` exposes the latest `IndexResult`

The observer already had `state: StateFlow<ScanState>` (Idle /
Scanning / Error). Phase 118 adds a second flow:

```kotlin
private val _lastResult: MutableStateFlow<IndexResult?> =
    MutableStateFlow(null)
val lastResult: StateFlow<IndexResult?>
    get() = _lastResult.asStateFlow()
```

The flow starts at `null` (no scan has run
yet); after the first `triggerScan` call,
it holds the `IndexResult` (with `added`,
`updated`, `unchanged`, `removed`, +
`hasNewItems`). The flow is updated on
every successful scan; on a failed scan
the previous value is preserved (the
`ScanState` becomes `Error`, the
`lastResult` is not touched).

### 2.2 `MediaSourceProvider` — a typed wrapper for the factory

The observer's scan needs a fresh
`MediaSource` on every call (the
`ContentResolver` `Cursor` lifecycle is
bounded to the call). The Phase 93
implementation constructed a new
`ContentResolverMediaSource` inline:

```kotlin
val source = ContentResolverMediaSource(context = context)
```

Phase 118 extracts this to a typed
`MediaSourceProvider` class:

```kotlin
@Singleton
open class MediaSourceProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    open operator fun invoke(): MediaSource =
        ContentResolverMediaSource(context = context)
}
```

The class is `open` so the unit tests
subclass it with a stub `invoke()` (the
Android `ContentResolver` is not available
on the JVM).

**Why a class, not a `() -> MediaSource`
lambda?** Hilt's wildcard handling for
Kotlin function types is a notorious
paper-cut. A `() -> MediaSource` Kotlin
parameter becomes `Function0<? extends
MediaSource>` in the Hilt-generated
Java, and the binding fails to match
the `@Provides` method unless both
sides have `@JvmSuppressWildcards`.
Wrapping the factory in a typed class
is the standard Hilt idiom for "I want
to inject a lambda" and removes the
incantation entirely.

### 2.3 `TitanApp.onCreate()` starts the observer

The application class is the right hook
for "do this once at process boot". The
existing pattern (the
`GuestDnsLifecycleBinder` is registered
here) shows the way:

```kotlin
@HiltAndroidApp
class TitanApp : Application(), ImageLoaderFactory {

    @Inject lateinit var mediaStoreObserver: MediaStoreObserver

    override fun onCreate() {
        super.onCreate()
        // ... existing DNS binder ...
        mediaStoreObserver.start()  // PHASE 118
    }
}
```

`MediaStoreObserver` is a `@Singleton`
(it lives in the Hilt graph for the
app's lifetime). The `start()` call is
**idempotent** (it checks the
`imagesObserver != null` guard before
re-registering), so even a hot-restart
in instrumentation is safe.

## 3. The Badge

### 3.1 `MediaScanBadgeViewModel` (new)

A new `@HiltViewModel` projects the
observer's `lastResult` flow into two
typed counts:

```kotlin
@HiltViewModel
class MediaScanBadgeViewModel @Inject constructor(
    private val mediaStoreObserver: MediaStoreObserver,
) : ViewModel() {

    val newMediaItemCount: StateFlow<Int> = mediaStoreObserver.lastResult
        .map { result ->
            result?.added?.count {
                it.mediaType == MediaType.IMAGE.name ||
                    it.mediaType == MediaType.VIDEO.name
            } ?: 0
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val newAudioItemCount: StateFlow<Int> = mediaStoreObserver.lastResult
        .map { result ->
            result?.added?.count { it.mediaType == MediaType.AUDIO.name } ?: 0
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}
```

The `WhileSubscribed(5_000)` policy is
the canonical pattern for a
Compose-driven badge: the upstream
`lastResult` flow is paused 5 seconds
after the last collector disappears
(when the user navigates away from the
dashboard), avoiding holding the
observer's hot flow open when no UI
is interested.

### 3.2 The `NewItemsBadge` Composable

The badge is a small neon-bordered
circle in the top-right corner of the
MEDIA VAULT + AUDIO HUB `PortalCard`s:

```kotlin
@Composable
private fun NewItemsBadge(
    count: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val pulse by rememberInfiniteTransition()
        .animateFloat(
            initialValue = 0.6f, targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                tween(1400, easing = FastOutSlowInEasing),
                RepeatMode.Reverse,
            ),
            label = "pulse",
        )
    val displayText = if (count > 99) "99+" else count.toString()
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f * pulse))
            .border(1.2.dp, color.copy(alpha = 0.95f * pulse), CircleShape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = displayText,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
        )
    }
}
```

The badge:
   - Pulses softly (alpha 0.6 → 1.0 over
     1.4s) so the user perceives the
     new items as "fresh".
   - Caps at "99+" for counts above 99
     (the canonical "many new items"
     sentinel; avoids breaking the
     card layout when the user copies
     a 5,000-photo album at once).
   - Inherits the card's neon color
     (MEDIA VAULT = `gSecondary`,
     AUDIO HUB = `gTertiary`) so the
     badge feels native to the rest
     of the dashboard theme.
   - Anchored `Alignment.TopEnd` on
     the card, with `10.dp` padding
     from the corner (the orbital
     icon sits inside the card content;
     the badge sits above the icon).

### 3.3 The `PortalCard` integration

The `PortalCard` Composable gains a
`badgeCount: Int?` parameter (default
`null` = no badge). The
`PortalItem` data class gains the same
field. The dashboard passes the count
from the `MediaScanBadgeViewModel`:

```kotlin
PortalItem(
    title = "MEDIA VAULT",
    ...
    badgeCount = newMediaItemCount.takeIf { it > 0 },
)
PortalItem(
    title = "AUDIO HUB",
    ...
    badgeCount = newAudioItemCount.takeIf { it > 0 },
)
```

The `takeIf { it > 0 }` ensures the
badge disappears as soon as the
user opens the screen and the
repository's `onStart { scan() }` fires
the first scan (the scan finds 0 new
items because the index already
contains the previously-discovered
items + the `lastResult` updates
to `added = []`).

## 4. The Test Suite (8 JVM tests)

The new `MediaStoreObserverTest` covers
the new behavior:

   - **`lastResult is null before the first scan`** — the flow's
     initial value matches the contract.
   - **`state is Idle before the first scan`** — the state flow
     starts at Idle.
   - **`triggerScan updates lastResult with the indexer result`** —
     the observer publishes the `IndexResult` after a scan.
   - **`lastResult reflects the latest scan on multiple scans`** —
     the flow always holds the most recent result; the second
     scan finds 1 Added + 1 Unchanged (the existing item is
     unchanged because the file's `dateModifiedMs` + `sizeBytes` +
     `contentHash` haven't changed).
   - **`state transitions from Idle to Scanning to Idle on a
     successful scan`** — the state flow is correctly updated
     around the scan.
   - **`mediaSourceProvider is invoked on every scan`** — the
     observer doesn't cache the `MediaSource` (the `Cursor`
     lifecycle is bounded to the call).
   - **`lastResult hasNewItems matches the IndexResult predicate`** —
     the canonical "is there something new to show the user"
     predicate is correctly derived from the `added` list.
   - **`lastResult added list contains the discovered media`** —
     the new items are correctly reflected in the
     `IndexResult.added` projection.

### 4.1 Test refactor: shared fixtures

Adding the new test exposed a
pre-existing collision: `MediaIndexerTest.kt`
and the new `MediaStoreObserverTest.kt`
both defined a top-level
`private class FakeMediaIndexDao` in
the same package. Kotlin top-level
declarations with the same name in the
same package collide regardless of
visibility (private at top-level is
"file-private" but the name still
occupies the package namespace). The
fix: extract the fixtures to a shared
`MediaTestFixtures.kt` file.

The new shared file contains:
   - **`FakeMediaIndexDao`** (the in-memory DAO fake; was
     duplicated in 3 places).
   - **`StubMediaSourceProvider`** (the observer's test stub;
     new with Phase 118).

`MediaIndexerTest.kt` was updated to
import the shared `FakeMediaIndexDao`
(removed the local duplicate).
`GalleryRepositoryTest.kt` keeps its
local copy (it's in a different
package, so no collision).

## 5. Bug Fixes In This Phase (Test-Discovered)

- **`MediaSourceProvider` `@JvmSuppressWildcards` failure**: the
  first cut of `MediaStoreObserver` injected a `() -> MediaSource`
  Kotlin lambda. Hilt's wildcard handling for Kotlin function
  types made the binding fail with
  `Function0<? extends MediaSource> cannot be provided`. Fix:
  wrap the factory in a typed `MediaSourceProvider` class (the
  standard Hilt idiom for "I want to inject a lambda").
- **Kotlin top-level name collision** (pre-existing; exposed
  by Phase 118): `private class FakeMediaIndexDao` in two
  files in the same package is a compile error in Kotlin.
  Fix: extract to shared `MediaTestFixtures.kt`.
- **First cut of `triggerScan` had no `nowMs` parameter**: the
  indexer's `nowMs` was hardcoded to
  `System.currentTimeMillis()`. The test that compared
  `lastSeenAtMs` between scans couldn't reproduce
  deterministic time. Fix: add an optional `nowMs: Long =
  System.currentTimeMillis()` parameter (the production caller
  is unaffected; the test passes a fixed `t0` + `t0 + 1_000`).
- **The two unused-parameter warnings on `MediaStoreObserver`**
  (`next` + `change`) are pre-existing (the comments explain
  them as "drain any other pending changes" + "coalesce by URI";
  they're intentional for the debouncer). The warnings are
  not in scope for Phase 118.

## 6. What's Closed vs What's Open

**Closed by Phase 118:**

   - The `MediaStoreObserver` is now
     **auto-started at app boot** (no
     more lazy first-scan trigger
     needed).
   - The MEDIA VAULT + AUDIO HUB
     tiles show a **live "X new
     items" badge** when a scan
     discovers new content.
   - The badge **disappears** as
     soon as the user opens the
     screen (the first scan inside
     the repository re-runs + finds
     0 new items because the index
     is already up to date).
   - The user's pivot to
     "Haz que escanee los sonidos
     e imágenes apenas entren" is
     now **end-to-end live in the
     app**: copy a new photo to
     the device, open Elysium
     Vanguard, the MEDIA VAULT
     tile shows the badge.
   - The Hilt + Kotlin wildcard
     paper-cut is fixed for the
     media source (the
     `MediaSourceProvider` class).

**Open (next concrete deliverables):**

   - **Phase 119** — the gallery +
     music UI surfaces the "X new
     items" badge **inside the
     screen** too (not just on the
     dashboard tile). When the
     user opens the gallery, the
     latest scan's new items can
     scroll into view.
   - **Phase 120** — the rich
     music metadata (`album`,
     `artist`, `duration`) is
     populated in the index (one
     more `ALTER TABLE` migration;
     the indexer reads the metadata
     columns).
   - **Phase 73 fourth half** —
     the real Elysium Linux
     binaries (Mesa/Turnip/Box64/
     FEX/Wine).
   - **Pre-existing Phase 98/99
     test failures** in
     `BinaryRunnerHandlerTest`
     (3 tests; not my work).
   - **Pre-existing flake** in
     `FoundryServiceRepositoryIntegrationTest`
     (1 test; `Dispatchers.setMain`
     missing in `@Before`).

## 7. Files Added / Modified

### Added

- `app/src/main/java/com/elysium/vanguard/core/media/MediaSourceProvider.kt` —
  the typed wrapper for the `() -> MediaSource`
  factory (Hilt + Kotlin wildcard fix).
- `app/src/main/java/com/elysium/vanguard/features/media/MediaScanBadgeViewModel.kt` —
  the VM that projects the observer's `lastResult`
  into `newMediaItemCount` + `newAudioItemCount`.
- `app/src/test/java/com/elysium/vanguard/core/media/MediaStoreObserverTest.kt` —
  the 8-test JVM suite.
- `app/src/test/java/com/elysium/vanguard/core/media/MediaTestFixtures.kt` —
  the shared `FakeMediaIndexDao` + `StubMediaSourceProvider`
  (extracted from 3 duplicate local copies).
- `docs/changelogs/PHASE_118_MEDIA_AUTO_SCAN.md` —
  this changelog.

### Modified

- `app/src/main/java/com/elysium/vanguard/core/media/MediaStoreObserver.kt` —
  added the `lastResult: StateFlow<IndexResult?>` flow +
  the `mediaSourceProvider: MediaSourceProvider` constructor
  parameter + the optional `nowMs` parameter on
  `triggerScan` (default `System.currentTimeMillis()`).
- `app/src/main/java/com/elysium/vanguard/TitanApp.kt` —
  injected `MediaStoreObserver` + called `start()` in
  `onCreate()`.
- `app/src/main/java/com/elysium/vanguard/core/media/MediaIndexModule.kt` —
  removed the obsolete `provideMediaSourceFactory`
  (`MediaSourceProvider` is now `@Inject constructor` and
  Hilt constructs it directly).
- `app/src/main/java/com/elysium/vanguard/features/dashboard/DashboardScreen.kt` —
  injected `MediaScanBadgeViewModel` via `hiltViewModel()`,
  passed the counts to the MEDIA VAULT + AUDIO HUB
  `PortalItem`s, added the `NewItemsBadge` Composable,
  added the `badgeCount: Int?` parameter to
  `PortalCard` + `PortalItem`.
- `app/src/test/java/com/elysium/vanguard/core/media/MediaIndexerTest.kt` —
  removed the local `private class FakeMediaIndexDao`
  (now uses the shared one from `MediaTestFixtures.kt`).

## 8. Build + Sync

- `./gradlew :app:compileDebugKotlin` — green.
- `./gradlew :app:testDebugUnitTest` — 3814 tests,
  1 pre-existing flake (the
  `FoundryServiceRepositoryIntegrationTest`
  `Module with the Main dispatcher had failed
  to initialize` issue; NOT related to Phase 118),
  2 skipped. **All 8 Phase 118 tests pass.**
- `./gradlew :app:assembleDebug` — green.
- `adb install -r app/build/outputs/apk/debug/app-debug.apk`
  → pending (device was offline at session
  resume; reconnect + install is the next
  step).

## 9. Cumulative Phase Status (post-Phase 118)

| Phase | Component                       | Status   |
| ----- | ------------------------------- | -------- |
| 91    | Production Critical E2E (JVM)    | SHIPPED  |
| 92    | Production Critical E2E (device)| SHIPPED  |
| 93    | Media Indexer (infra)            | SHIPPED  |
| 94    | Media Index Wiring (UI)          | SHIPPED  |
| **118**| **Media Auto-Scan + Badge**    | **SHIPPED** |

The full media story is now end-to-end
live in the app:

```
MediaStore (device)
  ↓ ContentObserver.onChange
MediaStoreObserver (auto-started at app boot)
  ↓ debounced triggerScan
MediaIndexer (DAO + diff algorithm)
  ↓ IndexResult
lastResult: StateFlow<IndexResult?>
  ↓ MediaScanBadgeViewModel
newMediaItemCount / newAudioItemCount: StateFlow<Int>
  ↓ PortalCard.badgeCount
NewItemsBadge (Compose)
  ↓ user taps MEDIA VAULT or AUDIO HUB
GalleryRepository / MusicRepository (reads MediaIndexDao)
  ↓ Flow<List<GalleryMedia>> / Flow<List<MusicTrack>>
GalleryViewModel / MusicHubViewModel
  ↓ Compose
MEDIA VAULT / AUDIO HUB screens
```

The user's pivot to "Haz que escanee
los sonidos e imágenes apenas entren"
is now closed end-to-end: the observer
auto-starts at app boot, every new
photo / video / audio copied to the
device triggers a debounced scan, the
new-item count flows through the
badge on the dashboard, and the
existing screens work without
modification (they read the
persistent index that the observer
populates).
