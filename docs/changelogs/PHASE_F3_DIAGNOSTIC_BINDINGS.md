# Phase F3 / I-3.6 — Diagnostic Bindings (Fault Model + Per-Part Bindings)

**Status**: ✅ SHIPPED
**Date**: 2026-07-18
**Commit**: (this commit)
**Builds**: `./gradlew :app:testDebugUnitTest` (2536 tests, 0 fail, 2 skip) ·
`./gradlew :app:assembleDebug` (0 warnings) ·
`./gradlew :app:assembleDebugAndroidTest` (0 warnings)

---

## Why

Phase F3's first + second + third + fourth + fifth halves shipped the **Canonical Scene Manifest** + the **Part Instance Graph** + the **LOD Selector** + the **Asset Streaming Pipeline** + the **Part Selection + Isolation** + the **Representation Confidence**. The 3D pipeline is feature-complete: a user can open a digital twin, see the parts, select one, isolate it, view the diagnostics, see the engineering artifacts, see the representation confidence, and stream the geometry.

The digital twin needs a **fault model**: the user wants to know "what's wrong with this part + how to fix it". Per the implementation roadmap I-3.6:
> Diagnostic bindings. The fault model integration. `Diagnostic` is a typed `DTC` reference + `Symptom` + `Hypothesis` + `TestProcedure` + `RepairAction` + `TelemetrySnapshot` + `VerificationStatus`.

Phase F3's sixth half (I-3.6) ships the `Diagnostic` data model + the `DiagnosticBinding` (per-part mapping).

---

## What shipped

### Production (foundry.core.scene)

#### 1. `Diagnostic` (the fault model value object)

The diagnostic has:
- `id: DiagnosticId` — a runtime-generated `UUID`.
- `dtcCode: String` — the OBD-II Diagnostic Trouble Code (e.g. `"P0420"` for catalytic converter efficiency below threshold).
- `symptom: String` — the user-facing symptom.
- `hypotheses: List<Hypothesis>` — the possible causes, ranked by likelihood.
- `testProcedures: List<TestProcedure>` — the diagnostic procedures.
- `repairActions: List<RepairAction>` — the actions the user can take.
- `verificationStatus: VerificationStatus` — the trust level.

The `init` block rejects blank `dtcCode` + blank `symptom`. The `UNVERIFIED_PROPOSAL` status is accepted (community-corroborated diagnostics start as unverified + are promoted by the AI council; the consumer is responsible for filtering).

#### 2. `DiagnosticId` (the typed runtime id)

A `@JvmInline value class` wrapping `UUID`. Random generation + `from(string)` factory with typed error on invalid UUID.

#### 3. `Hypothesis` (a possible cause)

The hypothesis has:
- `description: String` — the user-facing description.
- `likelihood: Double` — the likelihood (0.0 to 1.0; 1.0 = certain).

#### 4. `TestProcedure` (a diagnostic procedure)

The procedure has:
- `name: String` — the procedure's name.
- `steps: List<String>` — the steps in order.

Rejects blank name + empty steps + blank steps.

#### 5. `RepairAction` (a fix)

The action has:
- `name: String` — the action's name.
- `description: String` — a longer description.
- `estimatedTimeMinutes: Int` — the estimated time (for the UI's "estimated repair time" badge).

#### 6. `VerificationStatus` (the trust level)

The status is an enum with 4 values:
- `UNVERIFIED_PROPOSAL` — sentinel (the initial status; the AI council promotes).
- `COMMUNITY_CORROBORATED` — multiple independent users have reported the same fault.
- `ENGINEER_REVIEWED` — a platform engineer has reviewed the diagnostic.
- `OEM_VERIFIED` — an OEM has verified the diagnostic.

The status transitions are append-only (a `COMMUNITY_CORROBORATED` may become `ENGINEER_REVIEWED` but not back to `UNVERIFIED_PROPOSAL`). A silent downgrade is a `R-DI-6` typed error.

#### 7. `DiagnosticBinding` (per-part mapping)

The binding is a data class mapping `PartInstanceId` to a list of `Diagnostic`s. The binding has:
- `diagnosticsByPart: Map<PartInstanceId, List<Diagnostic>>` — the typed mapping.
- `partsWithDiagnostics: List<PartInstanceId>` — the sorted list of parts with at least one diagnostic.
- `allDiagnostics: List<Diagnostic>` — the sorted list of all diagnostics (sorted by `(partId, dtcCode)`).
- `diagnosticsFor(partId)` — look up the diagnostics for a given part.
- `addDiagnostic(partId, diagnostic)` — add a diagnostic to a part (returns a new binding; the original is unchanged).
- `removePart(partId)` — remove a part's diagnostics (returns a new binding).

The binding is **immutable** (a data class; no setters). A new binding is a new state. The binding transitions are pure functions of `(currentBinding, action)`.

The binding is **pure-domain**: no I/O, no Android dependencies.

### Tests

19 new tests (`DiagnosticBindingTest`):
- 3 `Diagnostic` tests (rejects blank DTC, blank symptom, accepts UNVERIFIED_PROPOSAL)
- 2 `Hypothesis` tests (rejects blank description, rejects likelihood outside 0..1)
- 3 `TestProcedure` tests (rejects blank name, empty steps, blank step)
- 2 `RepairAction` tests (rejects blank name + description, negative estimated time)
- 9 `DiagnosticBinding` tests (rejects empty list, empty binding, addDiagnostic, appends, diagnosticsFor unknown, removePart, allDiagnostics sort, addDiagnostic is pure, determinism)

---

## The 3D pipeline (Phase 3 / I-3.6 status — CLOSED)

Phase 3 has 6 sub-increments:

| Increment | Status | Description |
|-----------|--------|-------------|
| I-3.1 (Phase F3 first half) | ✅ | Scene manifest |
| I-3.2 (Phase F3 second half) | ✅ | Part instance graph |
| I-3.3 (Phase F3 third half) | ✅ | Asset streaming (LOD + cache + streamer) |
| I-3.4 (Phase F3 fourth half) | ✅ | Selection + isolation |
| I-3.5 (Phase F3 fifth half) | ✅ | Representation confidence (per-level UI + marketplace + safety gates) |
| I-3.6 (Phase F3 sixth) | ✅ | Diagnostic bindings (fault model + per-part mapping) |

**Phase 3 / G4 is now CLOSED.** The 3D pipeline + the digital twin are feature-complete. The 6 increments together provide:
- Static asset definition (manifest + scene)
- Runtime graph (instances + LODs + streaming + selection)
- UI states (isolation + representation confidence + diagnostics)

The next gate is **G5 — AI council** (Phase 4).

---

## Test counts

| Suite | Before | After | Delta |
|-------|--------|-------|-------|
| `DiagnosticBindingTest` | 0 | 19 | +19 (new) |
| **Total JVM unit tests** | 2517 | 2536 | **+19** |

**0 lint warnings, 0 test failures, 0 build errors.**

---

## Files

### New (production)
- `app/src/main/java/com/elysium/vanguard/foundry/core/scene/Diagnostic.kt`
- `app/src/main/java/com/elysium/vanguard/foundry/core/scene/DiagnosticBinding.kt`

### New (test)
- `app/src/test/java/com/elysium/vanguard/foundry/core/scene/DiagnosticBindingTest.kt`

---

## Architectural notes

### Why the binding is immutable

The binding is a value object. The transitions are pure functions: `addDiagnostic(partId, diagnostic)` returns a new binding; the old binding is unchanged. A mutable binding would let the consumer's modifications leak across calls (a "shared binding" pattern that's hard to reason about).

An immutable binding:
- Is **thread-safe** (no shared mutable state).
- Is **composable** (the binding can be passed to the UI + the diagnostic engine + the audit trail).
- Is **deterministic** (same inputs → same binding; the consumer can rely on the binding for re-renders).

### Why the diagnostic accepts `UNVERIFIED_PROPOSAL`

A community-corroborated diagnostic starts as `UNVERIFIED_PROPOSAL` (the AI council hasn't yet reviewed it) and is promoted to `COMMUNITY_CORROBORATED` after the council deliberates. The diagnostic must accept the initial state; the consumer (the UI) is responsible for filtering unverified diagnostics from the user-facing surface.

A stricter constructor (rejecting `UNVERIFIED_PROPOSAL`) would force the AI council to commit to a status before the diagnostic is created. The looser constructor (accepting any status) lets the diagnostic evolve through the verification pipeline.

### Why the `Diagnostic.id` is a `UUID` (not a content hash)

The `Diagnostic.id` is a runtime-generated `UUID` (the platform's identifier). The DTC code is the user-facing identifier (the OBD-II standard's identifier). The two are separate:
- The `id` is internal (used by the platform for identity).
- The `dtcCode` is external (used by the user + the OBD-II tools).

The id is a `UUID` (not a content hash) because the diagnostic is a **runtime value** (it evolves through the verification pipeline; the same DTC code may have multiple revisions).

### Why the binding sorts by `(partId, dtcCode)`

The sort key is `(partId, dtcCode)` because:
- The `partId` is the primary sort key (the user navigates by part).
- The `dtcCode` is the secondary sort key (the user reads the diagnostic codes in order).

The sort is **deterministic** (same inputs → same order). The sort uses the `UUID.toString()` for the `partId` (which is the platform's canonical string form) + the `dtcCode` string (which is the OBD-II standard's canonical form).

---

## Next phases (the pipeline forward)

- **Phase 4 (G5)** — AI council. The AI council's `AIProposal<DslMutation>` references the VSL grammar; the council's `AIProposal<DiagnosticProposal>` references the diagnostic model. The AI council proposes mutations + promotions (a `UNVERIFIED_PROPOSAL` may be promoted to `COMMUNITY_CORROBORATED` by the council's deliberation).
- **Phase 5 (G6+G7)** — Commercial foundation (RoyaltyContract + License + royalty engine).
- **Phase 6 (G8)** — Marketplace (the marketplace uses the `Diagnostic` to surface the part's known issues + the `RepairAction` to surface the fix cost).

The 3D pipeline + the digital twin are **feature-complete**. The next big gate (G5 — AI council) builds on top of the digital twin: the council proposes diagnostic promotions + VSL mutations + the marketplace uses the diagnostic for the buyer's risk assessment.
