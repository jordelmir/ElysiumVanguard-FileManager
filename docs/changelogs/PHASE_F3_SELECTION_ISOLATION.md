# Phase F3 / I-3.4 — Part Selection + Isolation (Digital Twin UI State)

**Status**: ✅ SHIPPED
**Date**: 2026-07-18
**Commit**: (this commit)
**Builds**: `./gradlew :app:testDebugUnitTest` (2504 tests, 0 fail, 2 skip) ·
`./gradlew :app:assembleDebug` (0 warnings) ·
`./gradlew :app:assembleDebugAndroidTest` (0 warnings)

---

## Why

Phase F3's first + second + third halves shipped the **Canonical Scene Manifest** + the **Part Instance Graph** + the **LOD Selector** + the **Asset Streaming Pipeline**. The graph is the static + runtime representation of the digital twin; the renderer streams the geometries + picks the LODs.

The digital twin needs a **user-facing surface**: the user selects a part + the renderer shows the part's diagnostics. Per the implementation roadmap I-3.4:

> The user-facing selection. The selection is the read-side state for the UI + the input to the diagnostic engine.

Phase F3's fourth half (I-3.4) ships the `PartSelection` (the read-side state) + the `PartSelector` (the operation layer that computes the visible / hidden / focused sets).

---

## What shipped

### Production (foundry.core.scene)

#### 1. `PartSelection` (the read-side state)

The selection is the typed value object that represents the current user selection. The selection has:
- `selectedId: PartInstanceId?` — the currently selected part's id. `null` when nothing is selected (the default state).
- `isolated: Boolean` — the isolation mode. `true` when the selected part is isolated (the rest of the graph is dimmed / hidden). `false` when the part is highlighted but the rest of the graph is visible.

The selection is **immutable** (a data class; no setters). The selection transitions are pure functions:
- `select(id)` — select a part. The selection transitions to the new part (not isolated; isolation is off when the selection changes).
- `deselect()` — clear the selection. Returns the `EMPTY` selection.
- `toggleIsolation()` — toggle the isolation mode. A no-op when no part is selected.

The selection's `init` block rejects `isolated=true` with no `selectedId` (isolation requires a part).

The selection is **pure-domain**: no I/O, no Android dependencies.

#### 2. `PartSelector` (the operation layer)

The selector is a stateless object that computes the visible / hidden / focused sets based on the graph + the selection. The selector:
- `visibleInstances(graph, selection)` — the part ids the renderer should draw. When the selection is empty or not isolated, the visible set is every instance in the graph. When the selection is isolated, the visible set is the selected part + its descendants.
- `hiddenInstances(graph, selection)` — the part ids the renderer should NOT draw (dimmed / hidden). The hidden set is the complement of the visible set.
- `resolveSelection(graph, selection)` — the underlying `PartInstance` of the selection. Returns `null` when the selection is empty OR the selected id is not in the graph (a stale selection).
- `focusedInstance(graph, selection)` — the "focused" instance (the part the UI shows in the "selected part" panel). Returns the selected instance, OR the first root when no part is selected, OR `null` when the graph is empty.

The visible instances are sorted by `(depth, label)` for deterministic rendering.

The selector is **stateless** (the graph + the selection are the inputs). The selector is **pure-domain**: no I/O, no Android dependencies.

### Tests

20 new tests (`PartSelectionTest`):
- 9 PartSelection tests (empty selection, select transitions, select same part no-op, select different resets isolation, deselect clears, toggle on, toggle off, toggle on empty no-op, rejects isolated=true with no selectedId)
- 5 PartSelector visible/hidden tests (empty selection, non-isolated, isolated, hidden, stale selection)
- 4 PartSelector resolveSelection + focusedInstance tests (resolve with selection, resolve with empty, resolve with stale, focused with selection, focused with empty)
- 2 determinism (sorted by depth then label, repeated calls produce same result)

---

## The 3D pipeline (Phase 3 / I-3.4 status)

Phase 3 has 6 sub-increments:

| Increment | Status | Description |
|-----------|--------|-------------|
| I-3.1 (Phase F3 first half) | ✅ | Scene manifest (`CanonicalSceneManifest` + `Canonical3DAsset` + LODs + bounds + transform + coordinate system + signature) |
| I-3.2 (Phase F3 second half) | ✅ | Part instance graph (`PartInstance` + `PartInstanceGraph` + read operations) |
| I-3.3 (Phase F3 third half) | ✅ | Asset streaming (LOD Selector + AssetGeometry + AssetContentStore + AssetCache + AssetStreamer) |
| I-3.4 (Phase F3 fourth half) | ✅ | Selection + isolation (`PartSelection` + `PartSelector`) |
| I-3.5 | TODO | Representation confidence (the `VehicleRepresentationLevel` integration; prominent UI display; gate for the marketplace; input to the safety gate) |
| I-3.6 | TODO | Diagnostic bindings (the fault model integration; `Diagnostic` is a typed `DTC` reference + `Symptom` + `Hypothesis` + `TestProcedure` + `RepairAction` + `TelemetrySnapshot` + `VerificationStatus`) |

The selection is the **user-facing surface** of the digital twin. The remaining 2 increments build on top of the selection: representation confidence (how the spec's level of detail is displayed) and diagnostic bindings (how the part's fault model is integrated).

---

## Test counts

| Suite | Before | After | Delta |
|-------|--------|-------|-------|
| `PartSelectionTest` | 0 | 20 | +20 (new) |
| **Total JVM unit tests** | 2484 | 2504 | **+20** |

**0 lint warnings, 0 test failures, 0 build errors.**

---

## Files

### New (production)
- `app/src/main/java/com/elysium/vanguard/foundry/core/scene/PartSelection.kt`
- `app/src/main/java/com/elysium/vanguard/foundry/core/scene/PartSelector.kt`

### New (test)
- `app/src/test/java/com/elysium/vanguard/foundry/core/scene/PartSelectionTest.kt`

---

## Architectural notes

### Why the selection is a data class (not a class with mutable state)

The selection is a value object. The transitions are pure functions: `select(id)` returns a new selection; the old selection is unchanged. A class with mutable state would let the consumer's modifications leak across calls (a "shared selection" pattern that's hard to reason about).

A data class:
- Is **immutable** (no `var` fields; transitions create new selections).
- Is **thread-safe** (no shared mutable state; two threads can read the same selection safely).
- Is **composable** (the selection can be passed to the renderer + the diagnostic engine + the audit trail).

### Why the selector is a stateless object (not a class)

The selector has no state; the graph + the selection are the inputs. A stateless object:
- Is **thread-safe** (no shared mutable state).
- Is **testable in isolation** (the tests pass the graph + the selection; no fixture setup).
- Is **composable** (the selector can be called from any consumer — the renderer, the diagnostic engine, the audit trail).

A class with state would add caching + memoization that the simple selector doesn't need (the visible / hidden computation is fast — a single linear scan + a filter).

### Why the selection is single-part (not multi-part)

A single-part selection is the simplest case. A multi-part selection would require:
- A `Set<PartInstanceId>` (instead of a single `PartInstanceId?`).
- A `Set<PartInstanceId>` for the "isolated" set (the user can isolate a subset of the selected parts).
- New operations (add to selection, remove from selection, etc.).

The single-part selection is the **MVP** (the minimum viable product). A future increment can add multi-select when the UI needs it.

### Why the "stale selection" returns the full graph

A selection may reference a part that was deleted from the graph (the user selected a part, then the graph was re-loaded with a new set of parts). The stale selection is a known-bad state; the safest behavior is to fall back to the full graph (the user sees everything).

A stricter behavior (throw on stale selection) would require the consumer to handle the error. A looser behavior (silently clear the selection) would hide the inconsistency. The full-graph fallback is the middle ground: the user sees the graph; the UI can highlight the stale selection as a warning.

---

## Next phases (the pipeline forward)

- **Phase F3 fifth half (I-3.5)** — Representation confidence. The `VehicleRepresentationLevel` integration (prominent UI display; gate for the marketplace; input to the safety gate).
- **Phase F3 sixth (I-3.6)** — Diagnostic bindings. The fault model integration (`Diagnostic` is a typed `DTC` reference + `Symptom` + `Hypothesis` + `TestProcedure` + `RepairAction` + `TelemetrySnapshot` + `VerificationStatus`).
- **Phase 4 (G5)** — AI council (typed `AIProposal` + multi-agent deliberation).

The selection is the **user-facing surface** of the digital twin. The remaining 2 increments build on top of the selection: representation confidence (how the spec's level of detail is displayed) and diagnostic bindings (how the part's fault model is integrated).
