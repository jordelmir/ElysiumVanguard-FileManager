# Phase F3 / I-3.3 (continued) — Asset Streaming Pipeline

**Status**: ✅ SHIPPED
**Date**: 2026-07-18
**Commit**: (this commit)
**Builds**: `./gradlew :app:testDebugUnitTest` (2484 tests, 0 fail, 2 skip) ·
`./gradlew :app:assembleDebug` (0 warnings) ·
`./gradlew :app:assembleDebugAndroidTest` (0 warnings)

---

## Why

Phase F3's third half (I-3.3) shipped the `LodSelector` — the consumer of the LODs. The selector is the picker; the **streaming pipeline** is the supplier.

Per `.ai/skills/06-3d-cad-asset-pipeline/SKILL.md` section 5:
> The pipeline is content-addressed; the pipeline's source is the canonical store; the pipeline's sink is the runtime cache.

Phase F3's I-3.3 (continued) ships the full asset streaming pipeline:

- **AssetGeometry** — the typed wrapper for a geometry (bytes + content hash + format version).
- **AssetContentStore** — the seam between the platform and the content-addressed storage. The production implementation reads from a content-addressed disk cache (a future Phase 4 milestone); the test implementation is an in-memory map.
- **AssetCache** — a thread-safe LRU cache for geometries. Bounded by `maxSizeBytes`; evicts the least-recently-used entry when the limit is exceeded. Records hit / miss / eviction stats for observability.
- **AssetStreamer** — the streaming pipeline that composes the cache + the store. The renderer calls `streamer.stream(contentHash)` per asset per frame; the cache keeps the hot assets in memory.

The streamer is the **typical entry point** for the 3D renderer. The streamer + the selector together close I-3.3.

---

## What shipped

### Production (foundry.core.scene)

#### 1. `AssetGeometry` (the typed geometry wrapper)

The geometry has:
- `contentHash: ContentHash` — the canonical id (the same bytes always produce the same hash).
- `formatVersion: String` — the format version (e.g. `glTF/2.0`, `USD/1.0`).
- `bytes: ByteArray` — the geometry's binary content (opaque to the platform).
- `sizeBytes: Int` — the size (computed).

The geometry is **opaque to the platform**: the platform stores + streams the bytes; the 3D renderer parses the bytes. The platform doesn't interpret the format.

The geometry is **equal by content**: two `AssetGeometry`s with the same `contentHash` + `formatVersion` + `bytes` are equal.

#### 2. `AssetContentStore` (the content-addressed seam)

The interface:
- `fetch(contentHash): Result<AssetGeometry>` — fetch the geometry; returns `Result.success(geometry)` on success, `Result.failure(AssetNotFound(...))` when the hash is not in the store, `Result.failure(Corrupted(...))` when the bytes don't match the declared hash.

The store is the **only** seam the platform uses to fetch a geometry. The platform doesn't care whether the bytes come from disk, network, or a hard-coded map.

The `InMemoryAssetContentStore` is the test implementation: a `ConcurrentHashMap<String, AssetGeometry>` keyed by `ContentHash.value`. The `put` method verifies the bytes' content hash before storing.

#### 3. `AssetCache` (the LRU runtime cache)

The cache has:
- `maxSizeBytes: Long` — the size limit (default: 100 MB; suitable for a mid-range device).
- `get(contentHash): AssetGeometry?` — look up a geometry; updates the LRU order; records a hit / miss.
- `put(geometry)` — store a geometry; evicts LRU entries until the cache fits the size limit.
- `clear()` — remove all entries (counters are NOT reset).
- `sizeBytes()`, `size()`, `hitRate()` — observability.
- `hitCount`, `missCount`, `evictionCount` — counters (thread-safe via `@Volatile`).

The cache is **thread-safe** (a `ConcurrentHashMap` with synchronized LRU eviction). The cache is **bounded** (the size limit prevents unbounded memory growth).

#### 4. `AssetStreamer` (the streaming pipeline)

The streamer:
1. Checks the cache. A hit returns the cached geometry (no store call).
2. On a miss, fetches from the store.
3. On a store success, puts the geometry in the cache + returns the geometry.
4. On a store failure, returns the failure (the cache is NOT polluted).

The streamer is the **typical entry point** for the 3D renderer. The streamer + the `LodSelector` together close I-3.3.

### Tests

17 new tests (`AssetStreamerTest`):
- 3 `AssetGeometry` tests (rejects blank format, rejects empty bytes, equal by content)
- 2 `InMemoryAssetContentStore` tests (returns AssetNotFound, put + fetch round-trip)
- 6 `AssetCache` tests (rejects non-positive max size, returns null for unknown, put + get, updates lastAccessedAtMs, evicts LRU when full, clear, hit rate)
- 5 `AssetStreamer` tests (cache hit short-circuits, cache miss fetches + caches, miss + hit serves from cache, no cache pollution on failure, constructor rejects non-empty cache)

---

## The 3D pipeline (Phase 3 / I-3.3 status — CLOSED)

Phase 3 / I-3.3 (Asset streaming) is now **CLOSED**. The full pipeline is:
- `LodSelector` (the picker) — picks the LOD based on screen size.
- `AssetContentStore` (the source) — fetches geometries from the content-addressed store.
- `AssetCache` (the runtime layer) — caches hot geometries; evicts LRU when full.
- `AssetStreamer` (the orchestrator) — composes the cache + the store.

The 3D renderer calls `streamer.stream(contentHash)` + `LodSelector.select(asset, screenSize)` per asset per frame. The pipeline is complete.

---

## Test counts

| Suite | Before | After | Delta |
|-------|--------|-------|-------|
| `AssetStreamerTest` | 0 | 17 | +17 (new) |
| **Total JVM unit tests** | 2467 | 2484 | **+17** |

**0 lint warnings, 0 test failures, 0 build errors.**

---

## Files

### New (production)
- `app/src/main/java/com/elysium/vanguard/foundry/core/scene/AssetGeometry.kt`
- `app/src/main/java/com/elysium/vanguard/foundry/core/scene/AssetContentStore.kt`
- `app/src/main/java/com/elysium/vanguard/foundry/core/scene/AssetCache.kt`
- `app/src/main/java/com/elysium/vanguard/foundry/core/scene/AssetStreamer.kt`

### New (test)
- `app/src/test/java/com/elysium/vanguard/foundry/core/scene/AssetStreamerTest.kt`

---

## Architectural notes

### Why the cache is a class (not a function)

The cache has **state** (the entries + the hit / miss / eviction counters + the current size). A function would have to take + return the state on every call. A class encapsulates the state.

The cache is **thread-safe** (a `ConcurrentHashMap` with `@Volatile` counters). The cache's `get` + `put` operations can be called from any thread (the 3D renderer runs on a dedicated thread; the store runs on an I/O thread).

### Why the streamer is a stateless class

The streamer composes the cache + the store. The streamer has no state of its own (the cache + the store are the state). The streamer is a **stateless class** (no `var` fields; the `cache` + `store` are `val`).

A function would have to take + return the cache + the store on every call. A class encapsulates the composition.

### Why the cache evicts on `put` (not on `get`)

A cache typically evicts on `put` (when the cache is full). The eviction is **proactive** (the cache ensures the limit is never exceeded). The alternative (evict on `get`) is **reactive** (the cache may temporarily exceed the limit).

A proactive eviction is simpler (the consumer never sees an oversize cache). A reactive eviction is more flexible (the cache may temporarily hold more data, but the consumer must handle the case).

The platform uses **proactive** eviction (simpler; the consumer doesn't need to handle oversize).

### Why the streamer doesn't pollute the cache on failure

A failed store call returns the failure (without caching anything). The cache is **only** populated on success. A failed cache miss → store success → cache put sequence is the typical path; a failed cache miss → store failure path is a no-op (the cache is unchanged).

A cache pollution (a failed call that returns a "broken" geometry from the cache) would be a hard bug: the consumer would receive a "broken" geometry on subsequent calls. The streamer's no-pollution invariant is a safety property.

---

## Next phases (the pipeline forward)

- **Phase F3 fourth half (I-3.4)** — Selection + isolation. The user-facing selection (read-side state for the UI + input to the diagnostic engine).
- **Phase F3 fifth half (I-3.5)** — Representation confidence. The `VehicleRepresentationLevel` integration (prominent UI display; gate for the marketplace; input to the safety gate).
- **Phase F3 sixth (I-3.6)** — Diagnostic bindings. The fault model integration (`Diagnostic` is a typed `DTC` reference + `Symptom` + `Hypothesis` + `TestProcedure` + `RepairAction` + `TelemetrySnapshot` + `VerificationStatus`).
- **Phase 4 (G5)** — AI council (typed `AIProposal` + multi-agent deliberation).

The streaming pipeline is **complete** (I-3.3 closed). The remaining 3 increments of Phase 3 build on top of the streaming pipeline: selection (how the user picks a part), representation confidence (how the spec's level of detail is displayed), and diagnostic bindings (how the part's fault model is integrated).
