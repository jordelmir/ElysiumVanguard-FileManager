# Phase F3 / I-3.5 — Representation Confidence (UI + Marketplace + Safety Gates)

**Status**: ✅ SHIPPED
**Date**: 2026-07-18
**Commit**: (this commit)
**Builds**: `./gradlew :app:testDebugUnitTest` (2517 tests, 0 fail, 2 skip) ·
`./gradlew :app:assembleDebug` (0 warnings) ·
`./gradlew :app:assembleDebugAndroidTest` (0 warnings)

---

## Why

Phase F3's first + second + third + fourth halves shipped the **Canonical Scene Manifest** + the **Part Instance Graph** + the **LOD Selector** + the **Asset Streaming Pipeline** + the **Part Selection + Isolation**. The 3D pipeline is feature-complete: a user can open a digital twin, see the parts, select one, isolate it, view the diagnostics, and stream the geometry.

The `VehicleRepresentationLevel` is the platform's **truth-model declaration** (per `.ai/STANDARDS.md` 2.1 + ADR-0002). The level is the input to the marketplace gate (per ADR-0011) + the safety gate (per skill 13). The level is displayed prominently in the UI (per the master prompt).

Phase F3's fifth half (I-3.5) ships the **Representation Confidence** — the user-facing bundle of the level + the UI hint the digital twin shows. The confidence is the per-level source of truth for the label, color, and gate results.

---

## What shipped

### Production (foundry.core.scene)

#### `RepresentationConfidence` (the user-facing bundle)

The confidence has:
- `level: RepresentationLevel` — the level itself.
- `displayLabel: String` — the user-facing label (e.g. `"OEM-Verified"`, `"Conceptual"`).
- `description: String` — a longer user-facing description (e.g. "Geometry validated against an OEM-shipped vehicle; units are correct; coordinate system matches the OEM standard; part numbers are traceable").
- `uiColor: UiColor` — the UI color (RED, ORANGE, YELLOW, BLUE, GREEN).
- `marketplaceEligible: Boolean` — the marketplace gate (per ADR-0011).
- `safetyGatePasses: Boolean` — the safety gate (per skill 13).

The confidence is **immutable** (a data class; no setters). The `init` block rejects:
- `UNKNOWN` level (the "level not set" sentinel).
- Blank `displayLabel`.
- Blank `description`.

The companion object provides:
- `forLevel(level)` — the lookup for every level.
- `isMarketplaceEligible(level)` — the marketplace gate (a convenience over `forLevel(level).marketplaceEligible`).
- `passesSafetyGate(level)` — the safety gate (a convenience over `forLevel(level).safetyGatePasses`).

The confidence is **pure-domain**: no I/O, no Android dependencies. The confidence is JVM-testable end-to-end.

### The 5 per-level confidences

| Level | Display Label | UI Color | Marketplace | Safety Gate |
|-------|---------------|----------|-------------|-------------|
| `OEM_EXACT` | `"OEM-Verified"` | GREEN | ✅ | ✅ |
| `OEM_PARTIAL` | `"OEM-Partial"` | BLUE | ✅ | ✅ |
| `PARAMETRIC_FUNCTIONAL` | `"Parametric"` | YELLOW | ✅ | ✅ |
| `CONCEPTUAL` | `"Conceptual"` | ORANGE | ❌ | ❌ |
| `VISUAL_ONLY` | `"Visual Only"` | RED | ❌ | ❌ |

The marketplace gate (per ADR-0011) is conservative: only the 3 verified levels are eligible. `CONCEPTUAL` and `VISUAL_ONLY` are rejected (the marketplace is for verified vehicles).

The safety gate (per skill 13) is the same: only the 3 verified levels pass. `CONCEPTUAL` and `VISUAL_ONLY` fail the safety gate (the gate requires verified engineering data).

### Tests

13 new tests (`RepresentationConfidenceTest`):
- 5 per-level confidence tests (every level has the right label, color, and gate results)
- 4 reject invalid (UNKNOWN by forLevel, UNKNOWN by constructor, blank displayLabel, blank description)
- 2 gate predicate tests (marketplace + safety gates for every level)
- 2 determinism (same level → same confidence, different levels → different confidences)

---

## The 3D pipeline (Phase 3 / I-3.5 status)

Phase 3 has 6 sub-increments:

| Increment | Status | Description |
|-----------|--------|-------------|
| I-3.1 (Phase F3 first half) | ✅ | Scene manifest |
| I-3.2 (Phase F3 second half) | ✅ | Part instance graph |
| I-3.3 (Phase F3 third half) | ✅ | Asset streaming (LOD + cache + streamer) |
| I-3.4 (Phase F3 fourth half) | ✅ | Selection + isolation |
| I-3.5 (Phase F3 fifth half) | ✅ | Representation confidence (the level's user-facing bundle + the marketplace + safety gates) |
| I-3.6 | TODO | Diagnostic bindings (the fault model integration; `Diagnostic` is a typed `DTC` reference + `Symptom` + `Hypothesis` + `TestProcedure` + `RepairAction` + `TelemetrySnapshot` + `VerificationStatus`) |

The confidence is the **per-level source of truth** for the UI + the marketplace + the safety gates. The remaining 1 increment (I-3.6 — diagnostic bindings) builds on top of the confidence: the diagnostic engine uses the confidence to decide what diagnostics to show + what safety assertions to enforce.

---

## Test counts

| Suite | Before | After | Delta |
|-------|--------|-------|-------|
| `RepresentationConfidenceTest` | 0 | 13 | +13 (new) |
| **Total JVM unit tests** | 2504 | 2517 | **+13** |

**0 lint warnings, 0 test failures, 0 build errors.**

---

## Files

### New (production)
- `app/src/main/java/com/elysium/vanguard/foundry/core/scene/RepresentationConfidence.kt`

### New (test)
- `app/src/test/java/com/elysium/vanguard/foundry/core/scene/RepresentationConfidenceTest.kt`

---

## Architectural notes

### Why the confidence is a data class (not a class with mutable state)

The confidence is a value object. The lookup is a pure function: `forLevel(level)` returns the same confidence for the same level. A class with mutable state would let the consumer's modifications leak across calls.

A data class:
- Is **immutable** (no `var` fields; the consumer cannot modify the confidence).
- Is **thread-safe** (no shared mutable state; two threads can read the same confidence safely).
- Is **composable** (the confidence can be passed to the UI + the marketplace + the safety gate; each consumer pattern-matches on the level + reads the relevant field).

### Why the gates are predicates (not methods on `RepresentationLevel`)

The gates are platform-wide rules (per ADR-0011 + per skill 13). Putting the gates on `RepresentationLevel` would couple the level to the platform's rules. A future increment may add a new level; the platform's rules would change; the level's behavior would change.

The gates are on the `RepresentationConfidence` companion object (top-level functions on the level's user-facing bundle). The gates are the platform's source of truth; the level is the schema's source of truth. The two are separate.

### Why `CONCEPTUAL` and `VISUAL_ONLY` are NOT marketplace-eligible

The marketplace is for verified vehicles. A `CONCEPTUAL` vehicle is a placeholder (dimensions are nominal, not validated). A `VISUAL_ONLY` vehicle is a render (no engineering claim). The marketplace rejects both because:
- A buyer needs verified engineering data to make a purchase decision.
- A `Settlement` requires verified provenance (per the foundry's commercial foundation).
- The platform's reputation depends on the marketplace being a verified-only channel.

A future increment may add a "conceptual marketplace" for early-stage design exploration, but that's a separate channel from the verified marketplace.

### Why `PARAMETRIC_FUNCTIONAL` IS marketplace-eligible

A `PARAMETRIC_FUNCTIONAL` vehicle is defined parametrically (mathematically) and is functionally accurate. The visual rendering is approximate, but the engineering data is real. The marketplace accepts the vehicle because:
- The buyer can verify the parameters (the math is deterministic).
- The buyer can use the vehicle for engineering decisions (the dimensions are accurate).
- The buyer can purchase the parametric definition + the visual approximation as a "starter kit".

The marketplace tags the vehicle with a YELLOW confidence badge (per the UI color), so the buyer knows the visual rendering is approximate.

---

## Next phases (the pipeline forward)

- **Phase F3 sixth (I-3.6)** — Diagnostic bindings. The fault model integration (`Diagnostic` is a typed `DTC` reference + `Symptom` + `Hypothesis` + `TestProcedure` + `RepairAction` + `TelemetrySnapshot` + `VerificationStatus`).
- **Phase 4 (G5)** — AI council (typed `AIProposal` + multi-agent deliberation).
- **Phase 5 (G6+G7)** — Commercial foundation (RoyaltyContract + License + royalty engine).

The confidence is the **per-level truth model** for the digital twin. The diagnostic engine (I-3.6) uses the confidence to decide what diagnostics to show: a `CONCEPTUAL` vehicle doesn't have validated DTCs (the DTCs are inferred from the parametric model); a `VISUAL_ONLY` vehicle doesn't have DTCs at all.
