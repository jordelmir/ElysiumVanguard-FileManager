# Phase F3 / I-3.1 — Canonical Scene Manifest (3D Pipeline Foundation)

**Status**: ✅ SHIPPED
**Date**: 2026-07-18
**Commit**: (this commit)
**Builds**: `./gradlew :app:testDebugUnitTest` (2438 tests, 0 fail, 2 skip) ·
`./gradlew :app:assembleDebug` (0 warnings) ·
`./gradlew :app:assembleDebugAndroidTest` (0 warnings)

---

## Why

Phase F2 shipped the schema + parser + validator + constraint engine + compilation pipeline + editor support (G2 + G3 closed). The next gate is **G4 — Digital twin + 3D pipeline**.

Per `.ai/skills/04-vehicle-dsl-compiler/SKILL.md` section 13 (Scene-manifest generator) + the implementation roadmap I-3.1:

> The manifest is a list of `Canonical3DAsset` references + their `LOD` selection + their `Transform` + their `CoordinateSystem` + their parent-child relationship. The manifest is signed; the manifest's content hash is the canonical id.

Phase F3's first half (I-3.1) ships the **Canonical Scene Manifest** — the typed input the 3D renderer (skill 06) + the digital twin (skill 07) consume. The manifest is the bridge from the schema (Phase F2) to the 3D pipeline (Phase F3).

The Phase 1 `SceneManifest` was a stub (string IDs + LOD placeholders). The Phase 3 `CanonicalSceneManifest` is the full typed shape the 3D pipeline consumes.

---

## What shipped

### Production (foundry.core.scene)

#### 1. `CanonicalSceneManifest` (the new manifest)

The manifest is the typed shape the 3D pipeline consumes. The manifest has:
- `revisionContentHash: ContentHash` — the `VehicleRevision`'s content hash (the manifest is bound to a specific compilation).
- `assets: List<Canonical3DAsset>` — the typed list of 3D assets.
- `coordinateSystem: CoordinateSystem` — the manifest-level coordinate system (default: `LOCAL`).
- `representationLevel: RepresentationLevel` — the user-facing declaration of "what level of detail the scene is".
- `signature: Signature` — the manifest's signature (verified at load time).
- `contentHash: ContentHash` — the manifest's canonical id (computed from the canonical form).

The manifest's `init` block validates:
- `assets` is non-empty.
- `representationLevel` is not `UNKNOWN`.
- Every asset's `parentId` references an asset in the same manifest.
- The asset graph is acyclic (a cycle would cause infinite recursion at render time).

The manifest is **content-addressed** (the content hash is the canonical id). The manifest is **signed** (the signature binds the manifest to the producer).

The companion object provides:
- `buildCanonicalForm(...)` — the canonical form of the manifest (the deterministic UTF-8 byte sequence used for hashing + signature verification).
- `verifySignature(manifest, expectedSignature)` — verifies the manifest's signature against the expected signature.

#### 2. `Canonical3DAsset` (the typed asset reference)

The asset is the typed reference to a 3D model. The asset has:
- `id: AssetId` — the content hash of the asset's geometry (a typed value class wrapping `String`).
- `label: String` — the user-facing name.
- `lods: List<AssetLod>` — the LOD variants (LOD0 = highest detail).
- `bounds: AssetBounds` — the AABB.
- `transform: AssetTransform` — the position + rotation + scale.
- `parentId: AssetId?` — the parent in the part instance graph (`null` = root).
- `coordinateSystem: CoordinateSystem` — the asset's coordinate system.

The asset's `init` block validates:
- `label` is non-blank.
- `lods` is non-empty.
- `lods` is sorted by `level` ascending.
- `lods[0].level == 0` (LOD0 is the always-available fallback).
- The asset is not its own parent.

The asset's `canonicalForm()` is the deterministic UTF-8 byte sequence used for the asset's content hash + the manifest's canonical form.

#### 3. `AssetLod` (a single level-of-detail)

The LOD is a single level-of-detail variant. The LOD has:
- `level: Int` — 0 = highest detail, higher = lower detail.
- `geometryHash: ContentHash` — the content hash of the geometry.
- `bounds: AssetBounds` — the AABB of this specific LOD.
- `triangleCount: Int` — the triangle count (for GPU cost estimation).
- `targetScreenSize: Int` — the screen size in pixels at which the renderer switches to this LOD.

#### 4. `AssetBounds` (axis-aligned bounding box)

The AABB has `min: Vector3` + `max: Vector3`. The `init` block validates `min <= max` for every axis.

#### 5. `Vector3` (3D vector)

A simple `Double`-precision 3D vector. Constants: `ZERO`, `UNIT_X`, `UNIT_Y`, `UNIT_Z`.

#### 6. `AssetTransform` (position + rotation + scale)

The transform has `position: Vector3` + `rotation: Quaternion` + `scale: Vector3`. The `init` block validates that `scale > 0` on every axis. Constant: `IDENTITY`.

#### 7. `Quaternion` (3D rotation)

A unit quaternion with `w + xi + yj + zk`. The `init` block validates that the norm is > 0. Constant: `IDENTITY`.

#### 8. `CoordinateSystem` (the coordinate system enum)

The enum: `LOCAL` (relative to parent's transform), `WORLD` (relative to scene's origin), `MODEL` (relative to the model's bounding box), `VIEW` (relative to the camera — rare; only for HUD overlays).

#### 9. `AssetId` (the typed content hash)

A new typed value class wrapping `String` (not `UUID` — the value IS the content hash). The `fromHash(hash)` factory validates the hash is a 64-char SHA-256 hex string. Constant: `UNKNOWN` (the empty / unknown sentinel).

### `FoundryError` extension

A new `ArtifactIntegrityFailure(artifactId, reason)` variant for content-addressed asset + signature verification failures. The error is `NON_RETRYABLE` (a corrupted download is a hard failure).

### Tests

22 new tests (`CanonicalSceneManifestTest`):
- 1 happy path (manifest constructs + computes content hash)
- 1 determinism (same assets → same content hash)
- 3 content hash differs (different assets, different revision hash, different representation level)
- 4 reject invalid (empty assets, UNKNOWN level, orphan parent, self-parent)
- 1 signature verification
- 5 supporting types (AssetLod, AssetBounds, AssetTransform, Quaternion, Vector3/Quaternion/AssetTransform identities)
- 3 AssetId tests (rejects blank, accepts 64-char hex, rejects short/non-hex)
- 1 manifest is cyclic (a → b → a is rejected)
- 1 vector identities + Quaternion IDENTITY + AssetTransform IDENTITY
- 1 representationLevel != UNKNOWN

---

## The 3D pipeline (Phase 3 / I-3.1 status)

Phase 3 has 6 sub-increments:

| Increment | Status | Description |
|-----------|--------|-------------|
| I-3.1 (Phase F3 first half) | ✅ | Scene manifest (`CanonicalSceneManifest` + `Canonical3DAsset` + LODs + bounds + transform + coordinate system + signature) |
| I-3.2 | TODO | Part instance graph (the runtime graph; user can select + isolate + view diagnostics) |
| I-3.3 | TODO | Asset streaming (LOD streaming pipeline; content-addressed source + runtime cache) |
| I-3.4 | TODO | Selection + isolation (user-facing selection; read-side state for the UI) |
| I-3.5 | TODO | Representation confidence (the `VehicleRepresentationLevel` integration; prominent UI display) |
| I-3.6 | TODO | Diagnostic bindings (the fault model integration; `Diagnostic` is a typed `DTC` reference + `Symptom` + `Hypothesis` + `TestProcedure` + `RepairAction` + `TelemetrySnapshot` + `VerificationStatus`) |

The scene manifest is the **foundation** of the 3D pipeline. The remaining 5 increments build on top of it.

---

## Test counts

| Suite | Before | After | Delta |
|-------|--------|-------|-------|
| `CanonicalSceneManifestTest` | 0 | 22 | +22 (new) |
| **Total JVM unit tests** | 2416 | 2438 | **+22** |

**0 lint warnings, 0 test failures, 0 build errors.**

---

## Files

### New (production)
- `app/src/main/java/com/elysium/vanguard/foundry/core/scene/CanonicalSceneManifest.kt`
- `app/src/main/java/com/elysium/vanguard/foundry/core/scene/Canonical3DAsset.kt`

### New (test)
- `app/src/test/java/com/elysium/vanguard/foundry/core/scene/CanonicalSceneManifestTest.kt`

### Modified (production)
- `app/src/main/java/com/elysium/vanguard/foundry/core/ontology/ids/Ids.kt` — added `AssetId` value class
- `app/src/main/java/com/elysium/vanguard/foundry/core/ontology/primitives/FoundryError.kt` — added `ArtifactIntegrityFailure` variant

---

## Architectural notes

### Why the new `CanonicalSceneManifest` is separate from the Phase 1 `SceneManifest`

The Phase 1 `SceneManifest` was a stub (string IDs + LOD placeholders) used by `SceneManifestGenerator` as a Phase 1 placeholder. The Phase 3 `CanonicalSceneManifest` is the full typed shape the 3D pipeline consumes.

Keeping them separate:
- The Phase 1 code path (the stub generator) is unchanged.
- The Phase 3 code path (the 3D pipeline) uses the new typed shape.
- A future migration can convert the Phase 1 manifest to the Phase 3 manifest.

### Why the asset id is a `String` (not a `UUID`)

A `UUID` is a 128-bit random value. A 3D asset id is a **content hash** (a SHA-256 of the geometry). The `AssetId` value class wraps `String` (not `UUID`) because the value IS the content hash. The `fromHash` factory validates the hash is a 64-char SHA-256 hex string.

### Why the manifest is signed

The manifest is the input to the 3D renderer + the digital twin. A tampered manifest could:
- Reference an asset that's not in the canonical store (the renderer fails to load the geometry).
- Reference an asset with a tampered geometry (the digital twin shows a wrong part).
- Change a part's parent (the part instance graph is broken).

The signature binds the manifest to the producer. The signature is verified at load time; a tampered manifest is rejected.

### Why the asset graph must be acyclic

A cycle in the parent-child graph would cause infinite recursion at render time (asset A's transform is computed relative to B; B's transform is computed relative to A). The `init` block rejects cycles. The check is a BFS from each root; a visited node means a cycle.

---

## Next phases (the pipeline forward)

- **Phase F3 second half (I-3.2)** — Part instance graph. The runtime graph (the user can select a part, isolate it, view its diagnostics, see its `EngineeringArtifact` references, trigger a `RepairAction`).
- **Phase F3 third half (I-3.3)** — Asset streaming. The LOD streaming pipeline (content-addressed source + runtime cache).
- **Phase F3 fourth half (I-3.4)** — Selection + isolation. The user-facing selection (read-side state for the UI + input to the diagnostic engine).
- **Phase F3 fifth half (I-3.5)** — Representation confidence. The `VehicleRepresentationLevel` integration (prominent UI display; gate for the marketplace; input to the safety gate).
- **Phase F3 sixth (I-3.6)** — Diagnostic bindings. The fault model integration (`Diagnostic` is a typed `DTC` reference + `Symptom` + `Hypothesis` + `TestProcedure` + `RepairAction` + `TelemetrySnapshot` + `VerificationStatus`).
- **Phase 4 (G5)** — AI council (typed `AIProposal` + multi-agent deliberation). The AI council proposes `DslMutation`s that the validator + the engine + the 3D pipeline must approve.

The scene manifest is the **foundation** of the 3D pipeline. The remaining 5 increments build on top of it.
