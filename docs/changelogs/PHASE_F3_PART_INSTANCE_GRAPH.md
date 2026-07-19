# Phase F3 / I-3.2 — Part Instance Graph (Digital Twin Runtime)

**Status**: ✅ SHIPPED
**Date**: 2026-07-18
**Commit**: (this commit)
**Builds**: `./gradlew :app:testDebugUnitTest` (2457 tests, 0 fail, 2 skip) ·
`./gradlew :app:assembleDebug` (0 warnings) ·
`./gradlew :app:assembleDebugAndroidTest` (0 warnings)

---

## Why

Phase F3's first half (I-3.1) shipped the **Canonical Scene Manifest** — the typed input the 3D pipeline + the digital twin consume. The manifest is the static definition: the assets + the parent-child relationships + the LODs.

The manifest is **static**. The digital twin needs a **runtime** representation: a graph of `PartInstance`s the user can select + isolate + view diagnostics + see engineering artifacts + trigger repair actions.

Per the implementation roadmap I-3.2:
> The runtime graph. The graph is the live representation of the vehicle: the user can select a part, isolate it, view its diagnostics, see its `EngineeringArtifact` references, and trigger a `RepairAction`.

Phase F3's second half (I-3.2) ships the `PartInstanceGraph` — the runtime representation built from the manifest + the read operations the digital twin consumes.

---

## What shipped

### Production (foundry.core.scene)

#### 1. `PartInstance` (a runtime instance of a 3D asset)

The `PartInstance` is the runtime representation of a `Canonical3DAsset` in the digital twin. The instance has:
- `id: PartInstanceId` — a runtime-generated id (a `UUID`).
- `assetId: AssetId` — the typed reference to the `Canonical3DAsset` the instance is built from.
- `parentInstanceId: PartInstanceId?` — the parent instance (for the graph).
- `displayLabel: String` — the user-facing label.
- `engineeringArtifactRefs: List<EngineeringArtifactId>` — typed references to the engineering artifacts that document this part.
- `repairActions: List<RepairActionId>` — typed references to the repair actions the user can trigger.

The instance is **immutable** (data class + no setters). The instance rejects:
- Blank `displayLabel`.
- Self-parent (the instance cannot be its own parent).

#### 2. `PartInstanceId` (the typed runtime id)

A `@JvmInline value class` wrapping `UUID` (per the platform's id convention). The id is runtime-generated (distinct from the asset's content hash; the same asset can be instantiated multiple times, each with a different `PartInstanceId`).

#### 3. `PartInstanceGraph` (the runtime graph)

The graph is the runtime representation of the digital twin. The graph:
- **Is built from a [CanonicalSceneManifest]** via `PartInstanceGraph.fromManifest(manifest, idFactory)`. The factory is the seam that lets the caller plug in a deterministic id generator (for testing) or a `random()` generator (for production).
- **Is tree-structured** (each part has zero or one parent; the graph is a forest).
- **Is acyclic** (the `init` block rejects cycles; a cycle would cause infinite recursion at render time).
- **Is immutable** (data class + no setters; the content hash is the graph's canonical id).

The graph exposes:
- `roots` — the instances with no parent.
- `size` — the count of instances.
- `findById(id)` — look up an instance by id.
- `findByAssetId(assetId)` — look up instances by their underlying asset id.
- `childrenOf(parentId)` — the direct children of a given instance.
- `descendantsOf(rootId)` — all descendants of a given instance (recursive).
- `ancestorsOf(instanceId)` — the chain of ancestors (immediate parent → root).
- `depthOf(instanceId)` — the count of ancestors.
- `contentHash` — the graph's canonical id (computed from the canonical form).
- `canonicalForm()` — the deterministic UTF-8 byte sequence used to compute the content hash.

The graph's `init` block validates:
- Non-empty `instances`.
- Non-`UNKNOWN` `representationLevel`.
- Every parent reference points to an instance in the same graph (orphan check FIRST so a missing parent is reported as "parent not in graph", not as a cycle).
- The instance graph is acyclic (cycle check SECOND).

The graph is **pure-domain**: no I/O, no Android dependencies. The graph is JVM-testable end-to-end.

### Tests

19 new tests (`PartInstanceGraphTest`):
- 3 PartInstance tests (rejects blank label, rejects self-parent, accepts empty artifact + repair lists)
- 3 fromManifest tests (graph size matches manifest size, parent-child relationships preserved, deterministic id factory works)
- 4 reject invalid (empty instances, UNKNOWN level, orphan parent, cyclic graph)
- 7 graph read operations (roots, childrenOf, descendantsOf, ancestorsOf, depthOf, findById, findByAssetId)
- 2 determinism tests (content hash is deterministic for same instances, differs for different instances)

---

## The 3D pipeline (Phase 3 / I-3.2 status)

Phase 3 has 6 sub-increments:

| Increment | Status | Description |
|-----------|--------|-------------|
| I-3.1 (Phase F3 first half) | ✅ | Scene manifest (`CanonicalSceneManifest` + `Canonical3DAsset` + LODs + bounds + transform + coordinate system + signature) |
| I-3.2 (Phase F3 second half) | ✅ | Part instance graph (`PartInstance` + `PartInstanceGraph` + read operations) |
| I-3.3 | TODO | Asset streaming (LOD streaming pipeline; content-addressed source + runtime cache) |
| I-3.4 | TODO | Selection + isolation (user-facing selection; read-side state for the UI) |
| I-3.5 | TODO | Representation confidence (the `VehicleRepresentationLevel` integration; prominent UI display) |
| I-3.6 | TODO | Diagnostic bindings (the fault model integration; `Diagnostic` is a typed `DTC` reference + `Symptom` + `Hypothesis` + `TestProcedure` + `RepairAction` + `TelemetrySnapshot` + `VerificationStatus`) |

The graph is the **runtime** counterpart of the manifest. The next 4 increments build on top of the graph.

---

## Test counts

| Suite | Before | After | Delta |
|-------|--------|-------|-------|
| `PartInstanceGraphTest` | 0 | 19 | +19 (new) |
| **Total JVM unit tests** | 2438 | 2457 | **+19** |

**0 lint warnings, 0 test failures, 0 build errors.**

---

## Files

### New (production)
- `app/src/main/java/com/elysium/vanguard/foundry/core/scene/PartInstance.kt`
- `app/src/main/java/com/elysium/vanguard/foundry/core/scene/PartInstanceGraph.kt`

### New (test)
- `app/src/test/java/com/elysium/vanguard/foundry/core/scene/PartInstanceGraphTest.kt`

---

## Architectural notes

### Why the graph is built from the manifest (not from a separate API)

The graph is the **runtime** counterpart of the **static** manifest. The manifest declares the assets + the parent-child relationships; the graph instantiates them as runtime instances.

Building the graph from the manifest:
- **Single source of truth** — the assets are declared once (in the manifest); the graph is derived.
- **Deterministic conversion** — the `fromManifest` factory is a pure function of the manifest; the same manifest produces the same graph.
- **Content-addressed** — the manifest's content hash is the input; the graph's content hash is the output (the hash is content-derived).

### Why the graph is a forest (not a single tree)

A `CanonicalSceneManifest` may have multiple roots (e.g. a vehicle with two separate powertrains). The graph is a forest (a collection of trees), not a single tree. The `roots` property exposes the forest's roots; the `descendantsOf` operation is per-root.

### Why the orphan check is BEFORE the cycle check

The orphan check (`parent not in graph`) is a simpler error than the cycle check (which requires a BFS). A developer fixing the issue wants to see the simpler error first. Reordering the checks:
- Orphan → "parent not in graph" (simple error).
- Cycle → "instance graph has a cycle" (complex error).

The reverse order would report a cycle for an orphan (the BFS from roots would not visit the orphan; the orphan's visited count would be < instances.size, which the BFS interprets as a cycle).

### Why the `PartInstanceId` is a `UUID` (not a content hash)

The `PartInstanceId` is a **runtime-generated** id; the same `Canonical3DAsset` can be instantiated multiple times in the graph (e.g. 4 wheels for a car). The asset's content hash is the asset's id; the instance's id is a `UUID` (distinct from the content hash).

The instance is content-addressed by **composition** (the `assetId` + `parentInstanceId` + `displayLabel` + `engineeringArtifactRefs` + `repairActions` produces the same content address), but the `id` is a runtime-generated `UUID` for efficiency (the consumer doesn't need to hash the instance to compare).

---

## Next phases (the pipeline forward)

- **Phase F3 third half (I-3.3)** — Asset streaming. The LOD streaming pipeline (content-addressed source + runtime cache).
- **Phase F3 fourth half (I-3.4)** — Selection + isolation. The user-facing selection (read-side state for the UI + input to the diagnostic engine).
- **Phase F3 fifth half (I-3.5)** — Representation confidence. The `VehicleRepresentationLevel` integration (prominent UI display; gate for the marketplace; input to the safety gate).
- **Phase F3 sixth (I-3.6)** — Diagnostic bindings. The fault model integration (`Diagnostic` is a typed `DTC` reference + `Symptom` + `Hypothesis` + `TestProcedure` + `RepairAction` + `TelemetrySnapshot` + `VerificationStatus`).
- **Phase 4 (G5)** — AI council (typed `AIProposal` + multi-agent deliberation).

The graph is the **runtime state** of the digital twin. The remaining 4 increments build on top of the graph: streaming (how the assets are loaded), selection (how the user picks a part), representation confidence (how the spec's level of detail is displayed), and diagnostic bindings (how the part's fault model is integrated).
