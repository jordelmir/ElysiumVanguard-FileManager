# Phase F2 / I-2.5 — Compatibility Constraint Engine

**Status**: ✅ SHIPPED
**Date**: 2026-07-18
**Commit**: (this commit)
**Builds**: `./gradlew :app:testDebugUnitTest` (2394 tests, 0 fail, 2 skip) ·
`./gradlew :app:assembleDebug` (0 warnings) ·
`./gradlew :app:assembleDebugAndroidTest` (0 warnings)

---

## Why

Phase F2's third half shipped the **Spec Validator** — the cross-aggregate invariant checker (mechanically impossible combinations). Phase F2's fourth half shipped the **Compilation Pipeline** (the 18-step orchestrator).

The validator catches **invariants** (a combination is mechanically impossible). The platform also needs a **constraint engine** that catches **constraints** (a combination doesn't meet a market / regulatory standard or has a better alternative).

The constraint engine is **step 8** of the 18-step pipeline (per `.ai/skills/04-vehicle-dsl-compiler/SKILL.md` section 6):
> 8. **Compatibility checking.** Every pair of `PartInstance`s in the `VehicleDefinition` is checked for compatibility. An incompatible pair is a typed `CompatibilityConstraintViolation` error.

Phase F2's fifth half ships the constraint engine — a step 8 implementation that operates on the spec's high-level fields (body, propulsion, driveline) rather than the lower-level `PartInstance` graph (which is a future phase). The engine catches the most common cross-aggregate constraints:

- **REGULATORY** — a market standard (e.g. EU B-segment SUV requires wheelbase >= 2.5m)
- **OPTIMIZATION** — a better combination exists (e.g. 5-seat pickup with RWD is unusual; AWD is more common)
- **SOFT** — a market preference (e.g. diesel in a 2-seater is unusual)

---

## What shipped

### Production (foundry.core.dsl.compatibility)

#### 1. `CompatibilityConstraintEngine` interface (the contract)

The constraint engine takes a `CompiledVehicleSpec` and returns a list of `CompilationDiagnostic`. The engine is:
- **Declarative** — a constraint is a typed expression; the engine evaluates the expression.
- **Deterministic** — same spec → same diagnostic list.
- **Typed** — a constraint violation is a typed `CompilationDiagnostic`.
- **Pure-domain** — no I/O, no Android dependencies.

#### 2. `CompatibilityConstraint` interface (a single constraint)

A constraint is a discrete, named check. The constraint has:
- A stable `code` (the `VCOMP-CONSTRAINT-XXX-YYY` identifier)
- A human-readable `name` (the editor's "live validation" panel)
- A `check(spec)` function returning a list of `CompilationDiagnostic`

The constraint is pure + deterministic. The constraint is composable (a constraint set is a list of constraints).

#### 3. `DefaultCompatibilityConstraintEngine` (the default implementation)

The default engine applies a `CompatibilityConstraintSet` of constraints in order and aggregates the diagnostics.

#### 4. `CompatibilityConstraintSet` (a named collection of constraints)

A constraint set is named + versioned (per `.ai/skills/04-vehicle-dsl-compiler/SKILL.md` section 6 step 22). The set validates at construction (unique codes, non-blank name + version).

#### 5. The 8 default constraints

The default constraint set is the 8 cross-aggregate constraints the platform ships:

| Constraint | Severity | Description |
|-----------|----------|-------------|
| `VCOMP-CONSTRAINT-SUV-WHEELBASE` | REGULATORY | SUV body requires wheelbase >= 2.5m |
| `VCOMP-CONSTRAINT-V8-DISPLACEMENT` | REGULATORY | V8+ engine requires displacement >= 4.0L |
| `VCOMP-CONSTRAINT-VAN-WHEELBASE` | REGULATORY | 9-seat van requires wheelbase >= 3.0m |
| `VCOMP-CONSTRAINT-HYBRID-TRANSMISSION` | REGULATORY | Hybrid requires multi-speed transmission |
| `VCOMP-CONSTRAINT-QUAD-PROPULSION` | REGULATORY | QUAD traction requires ELECTRIC or HYBRID |
| `VCOMP-CONSTRAINT-PICKUP-AWD` | OPTIMIZATION | 5-seat pickup with RWD is unusual |
| `VCOMP-CONSTRAINT-WAGON-2DOORS` | OPTIMIZATION | 2-door wagon is unusual |
| `VCOMP-CONSTRAINT-DIESEL-2SEAT` | OPTIMIZATION | Diesel in 2-seater is unusual |

### Tests

18 new tests (`DefaultCompatibilityConstraintEngineTest`):
- 1 happy path (Urban One spec passes all 8 constraints)
- 5 REGULATORY constraint tests (SUV wheelbase, V8 displacement, VAN wheelbase, HYBRID transmission, QUAD propulsion)
- 2 QUAD constraint variants (GASOLINE fails, ELECTRIC passes)
- 1 OPTIMIZATION pickup test
- 1 OPTIMIZATION wagon test
- 1 OPTIMIZATION diesel test
- 1 determinism test
- 5 constraint set integrity tests (unique codes, non-blank name + version, duplicate code rejection)

---

## Validator vs Constraint Engine

The platform has two distinct invariant checkers:

| Aspect | Validator (Phase F2 / I-2.4) | Constraint Engine (Phase F2 / I-2.5) |
|--------|-----------------------------|--------------------------------------|
| **What** | Cross-aggregate **invariants** | Cross-aggregate **constraints** |
| **Why** | Mechanically impossible combinations | Market / regulatory / optimization standards |
| **Severities** | HARD / SAFETY_CRITICAL / SOFT | REGULATORY / OPTIMIZATION / SOFT |
| **Block?** | HARD / SAFETY_CRITICAL block | REGULATORY blocks (per market) |
| **Examples** | `ELECTRIC` + `INLINE_4`; `V12` + `FWD`; `COUPE` + 4 doors | SUV wheelbase < 2.5m; V8 displacement < 4.0L; HYBRID + SINGLE_SPEED |

The validator is a **physical** check (the laws of physics). The constraint engine is a **market** check (the laws of a market). Both are deterministic + total + pure-domain.

---

## The 18-step pipeline (Phase 2 / I-2.5 status)

```
Step  1: Lexing / structured decoding  (parser - Phase F2 second half)
Step  2: Parsing                        (parser - Phase F2 second half)
Step  3: Schema validation              (validator - Phase F2 third half) ✓
Step  4: Unit normalization             (placeholder)
Step  5: Alias resolution               (placeholder)
Step  6: Applicability resolution       (placeholder)
Step  7: Dependency expansion           (placeholder)
Step  8: Compatibility checking         (constraint engine - this phase) ✓
Step  9: Constraint solving             (placeholder)
Step 10-18: ...
```

Two more steps are now implemented. The pipeline's placeholders (steps 4-7, 9-18) are the future work.

---

## Test counts

| Suite | Before | After | Delta |
|-------|--------|-------|-------|
| `DefaultCompatibilityConstraintEngineTest` | 0 | 18 | +18 (new) |
| **Total JVM unit tests** | 2376 | 2394 | **+18** |

**0 lint warnings, 0 test failures, 0 build errors.**

---

## Files

### New (production)
- `app/src/main/java/com/elysium/vanguard/foundry/core/dsl/compatibility/CompatibilityConstraintEngine.kt`
- `app/src/main/java/com/elysium/vanguard/foundry/core/dsl/compatibility/DefaultCompatibilityConstraintEngine.kt`

### New (test)
- `app/src/test/java/com/elysium/vanguard/foundry/core/dsl/compatibility/DefaultCompatibilityConstraintEngineTest.kt`

---

## Architectural notes

### Why the engine is separate from the validator

The validator checks **invariants** (a combination is mechanically impossible — the laws of physics). The engine checks **constraints** (a combination doesn't meet a market / regulatory standard — the laws of a market).

The two have:
- **Different severities** (validator: HARD / SAFETY_CRITICAL / SOFT; engine: REGULATORY / OPTIMIZATION / SOFT)
- **Different block semantics** (validator: blocks globally; engine: blocks per market)
- **Different rule authors** (validator: the platform; engine: the OEM / market regulator)

Separating them lets each evolve independently.

### Why the engine uses `CrossAggregateInvariantViolation`

The `CompilationDiagnostic.CrossAggregateInvariantViolation` variant (added in Phase F2 third half) is the typed diagnostic for cross-aggregate violations. The validator uses it; the constraint engine also uses it. The `ruleCode` field distinguishes the two (`VCOMP-RULE-*` for validator rules; `VCOMP-CONSTRAINT-*` for constraint engine rules).

### Why constraints are pluggable

A constraint set is named + versioned + pluggable. An OEM can ship a brand-specific constraint set (e.g. "Tesla doesn't produce V8 engines" is a Tesla-specific constraint). A market regulator can ship a region-specific constraint set (e.g. "EU B-segment standards" is an EU-specific constraint). The platform's default constraint set is the baseline; the OEM's / market's set is layered on top.

---

## Next phases (the pipeline forward)

- **Phase F2 sixth (I-2.8)** — Editor support (the live-validation surface that consumes the validator's diagnostics + the constraint engine's diagnostics + the pipeline's report). The editor is the user-facing surface for "show me the violations on every keystroke".
- **Phase 3 (G4)** — 3D pipeline (Scene manifest + LODs + asset validation). The 3D pipeline consumes the `Compilation` (steps 15, 16).
- **Phase 4 (G5)** — AI council (typed `AIProposal` + multi-agent deliberation). The AI council proposes `DslMutation`s that the validator + the engine must approve.

The constraint engine is the **market gate**. The validator is the **physics gate**. Both must pass before a spec is eligible for the marketplace (Phase 6).
