# Phase F3 / I-3.3 (partial) — LOD Selector

**Status**: ✅ SHIPPED
**Date**: 2026-07-18
**Commit**: (this commit)
**Builds**: `./gradlew :app:testDebugUnitTest` (2467 tests, 0 fail, 2 skip) ·
`./gradlew :app:assembleDebug` (0 warnings) ·
`./gradlew :app:assembleDebugAndroidTest` (0 warnings)

---

## Why

Phase F3's first + second halves shipped the **Canonical Scene Manifest** (the static asset definition) + the **Part Instance Graph** (the runtime graph). The assets carry a list of `AssetLod`s (level-of-detail variants); each LOD has a `targetScreenSize` (the screen size in pixels at which the renderer switches TO that LOD).

The asset declares the LODs; the renderer needs a way to **pick** the right LOD. The picker is the `LodSelector` — a pure function that takes an asset + a screen size and returns the LOD.

This is a partial I-3.3 (Asset streaming). The full I-3.3 scope includes the streaming pipeline (content-addressed source + runtime cache); the `LodSelector` is the consumer of the LODs (the smallest piece of I-3.3 that the renderer needs).

The streaming pipeline + the cache are future increments.

---

## What shipped

### Production (foundry.core.scene)

#### `LodSelector` (the LOD picker)

The selector is a pure function. The selection rule (per `.ai/skills/06-3d-cad-asset-pipeline/SKILL.md` section 6):

- The LOD with the **largest** `targetScreenSize` that is still `<= screenSize` (the highest detail that fits).
- The most-coarse LOD (the highest `level`) when the screen size is smaller than every LOD's `targetScreenSize` (the asset is viewed very far away).
- LOD0 (the highest detail) when the screen size is larger than every LOD's `targetScreenSize` (the asset is viewed very close).

The selector is:
- **Pure** — same asset + same screen size → same LOD.
- **Deterministic** — no randomness, no time-dependence.
- **Total** — every input produces a result.
- **Lightweight** — a single linear scan; O(n) where n is the number of LODs (typically 3-5).

The `selectAll(assets, screenSize)` helper is the typical call site for the 3D renderer: one LOD per asset per frame, in input order.

### Tests

10 new tests (`LodSelectorTest`):
- 1 selection rule (largest target that fits)
- 1 LOD0 fallback (screen size > all targets)
- 1 most-coarse fallback (screen size < all targets)
- 1 exact match (target == screen size)
- 1 single-LOD asset
- 1 rejects non-positive screen size
- 2 determinism (same input → same output; different inputs → different outputs)
- 2 `selectAll` (one pair per asset, empty asset list)

---

## The 3D pipeline (Phase 3 / I-3.3 status)

Phase 3 / I-3.3 (Asset streaming) is **partial**: the `LodSelector` is the consumer of the LODs. The streaming pipeline (content-addressed source + runtime cache) is a future increment.

The next step is the `AssetCache` (an in-memory LRU cache for asset geometries) + the `AssetStreamer` (the streaming pipeline that fetches from the content-addressed source + caches the result).

---

## Test counts

| Suite | Before | After | Delta |
|-------|--------|-------|-------|
| `LodSelectorTest` | 0 | 10 | +10 (new) |
| **Total JVM unit tests** | 2457 | 2467 | **+10** |

**0 lint warnings, 0 test failures, 0 build errors.**

---

## Files

### New (production)
- `app/src/main/java/com/elysium/vanguard/foundry/core/scene/LodSelector.kt`

### New (test)
- `app/src/test/java/com/elysium/vanguard/foundry/core/scene/LodSelectorTest.kt`

---

## Architectural notes

### Why the selector is a pure function (not a class)

The selector is a single rule (the largest target that fits). A pure function:
- Is testable in isolation (no dependencies, no state).
- Is composable (the renderer can call `select` per asset per frame).
- Is cacheable (the renderer can memoize the result; the result depends only on the inputs).

A class would add state (a cache, a configuration) that the simple selector doesn't need. The streaming pipeline's `AssetCache` (a future increment) is where state lives.

### Why the selector doesn't cache the result

The selector is a single linear scan — O(n) where n is the number of LODs (typically 3-5). The scan is sub-microsecond; the cache would add overhead (the cache lookup is the same order as the scan). The renderer can memoize the result externally (e.g. the 3D renderer caches the per-frame selection).

The streaming pipeline's `AssetCache` (a future increment) caches the **geometries**, not the LOD selection. The LOD selection is per-frame; the geometries are persistent.

### Why the fallback to LOD0 / most-coarse

A `Canonical3DAsset` MAY have LODs that don't cover the full screen-size range (e.g. LODs for `target = [4096, 2048, 1024]` but no LOD for `target = 256`). The selector's fallbacks ensure:
- **Screen too large** → LOD0 (the highest detail; the renderer can sub-pixel render).
- **Screen too small** → most-coarse LOD (the lowest detail; the renderer can cull).

Without fallbacks, the selector would have to throw on out-of-range screen sizes — a hard failure for an unbounded input (the camera can be anywhere).

---

## Next phases (the pipeline forward)

- **Phase F3 third half (I-3.3 continued)** — Asset cache + streaming pipeline. The `AssetCache` (in-memory LRU cache) + the `AssetStreamer` (the streaming pipeline that fetches from the content-addressed source + caches the result).
- **Phase F3 fourth half (I-3.4)** — Selection + isolation. The user-facing selection (read-side state for the UI + input to the diagnostic engine).
- **Phase F3 fifth half (I-3.5)** — Representation confidence. The `VehicleRepresentationLevel` integration (prominent UI display; gate for the marketplace; input to the safety gate).
- **Phase F3 sixth (I-3.6)** — Diagnostic bindings. The fault model integration (`Diagnostic` is a typed `DTC` reference + `Symptom` + `Hypothesis` + `TestProcedure` + `RepairAction` + `TelemetrySnapshot` + `VerificationStatus`).
- **Phase 4 (G5)** — AI council (typed `AIProposal` + multi-agent deliberation).

The `LodSelector` is the **first piece** of asset streaming (the consumer of the LODs). The remaining pieces (the cache + the streamer) build on top of it.
