# Phase F2 / I-2.6 + I-2.7 — Deterministic Compiler Pipeline + Compilation Report

**Status**: ✅ SHIPPED
**Date**: 2026-07-18
**Commit**: (this commit)
**Builds**: `./gradlew :app:testDebugUnitTest` (2376 tests, 0 fail, 2 skip) ·
`./gradlew :app:assembleDebug` (0 warnings) ·
`./gradlew :app:assembleDebugAndroidTest` (0 warnings)

---

## Why

Phase F2's third half shipped the **Spec Validator** — the cross-aggregate invariant checker that runs after the parser. The validator is step 3 of the 18-step compiler pipeline (per `.ai/skills/04-vehicle-dsl-compiler/SKILL.md` section 6).

The 18 steps are:

1. Lexing / structured decoding
2. Parsing
3. **Schema validation** (Phase F2 third half)
4. Unit normalization
5. Alias resolution
6. Applicability resolution
7. Dependency expansion
8. Compatibility checking
9. Constraint solving
10. Part selection
11. Assembly graph construction
12. Interface binding
13. Collision + packaging prechecks
14. Diagnostic binding
15. BOM generation
16. Scene-manifest generation
17. **Compilation report** (this phase)
18. **Artifact hashing** (this phase)

Phase F2's fourth half ships the **Compilation Pipeline** that orchestrates the implemented steps (3, 17, 18) + records the future steps as placeholders. The pipeline is the user-facing entry point for "compile a parsed spec"; the future phases implement the remaining steps and the placeholders become real implementations.

The fourth half also ships the **Compilation Report** — the user-facing report (errors + warnings + info notes + per-step results) that the editor shows after a compile.

---

## What shipped

### Production (foundry.core.compiler)

#### 1. `CompilationReport` (the user-facing report)

The compilation report is a typed value:

- `steps: List<Step>` — one per step in 1..18. Each step is a `Success` (with optional output) or `Failure` (with a typed diagnostic).
- `validationDiagnostics: List<CompilationDiagnostic>` — the validator's diagnostics (a flat list for the UI's "all diagnostics" view).
- `isBlocked: Boolean` — `true` when at least one step failed OR the validation has a blocking diagnostic.

The report has computed views:
- `errors: List<CompilationDiagnostic>` — the blocking diagnostics (the union of failed-step diagnostics + validation blocking diagnostics).
- `warnings: List<CompilationDiagnostic>` — the SOFT diagnostics from the validator.
- `optimizations: List<CompilationDiagnostic>` — the OPTIMIZATION diagnostics from the validator.
- `warningMessages: List<String>` — the messages of `errors + warnings` (the backward-compat with Phase 1's `Compilation.warnings`).

The report is **immutable** (data class + no setters). The report's `init` block validates that every step's number is in `1..18`.

#### 2. `CompilationReport.Step` (the per-step result)

A sealed class:
- `Success(stepNumber, stepName, output?)` — the step passed.
- `Failure(stepNumber, stepName, diagnostic)` — the step failed; the diagnostic is the typed error.

The step's `stepNumber` is the position in the 18-step pipeline (3 = Schema validation, 17 = Compilation report, 18 = Artifact hashing).

#### 3. `buildReport(steps, validationResult)` (the helper)

A top-level helper that builds a `CompilationReport` from a list of step results + a `ValidationResult`. The helper computes `isBlocked` (any failure OR validation invalid) and aggregates the diagnostics.

#### 4. `CompilationPipeline` (the 18-step orchestrator)

The pipeline runs the implemented steps:
- **Step 3 (Schema validation)** — runs the `SpecValidator`; records the result as a `Success` or `Failure` step.
- **Steps 4-16 (future)** — recorded as `Success` with a "not yet implemented (skipped)" note.
- **Step 17 (Compilation report)** — builds the `CompilationReport` from the per-step results + the validation diagnostics.
- **Step 18 (Artifact hashing)** — computes the SHA-256 of `compilation:v2|catalog=<rev>|compiler=<ver>|ruleset=<className>|<spec.canonicalForm()>`.

The pipeline is:
- **Deterministic** (per skill 04 section 7) — same `(spec, catalogRevision, compilerVersion, validator)` → same `Compilation.contentHash`.
- **Total** — every spec produces a `Result<Compilation>`; a failed validation still produces a `Compilation` (the report is the failure channel).
- **Pure-domain** — no I/O, no Android dependencies.

#### 5. `Compilation` (extended)

The `Compilation` data class is extended with an optional `report: CompilationReport?` field. The field is `null` for the Phase 1 `DeterministicVehicleCompiler` (backward compatibility) and non-null for the Phase 2 `CompilationPipeline`.

The Phase 1 constructor signature `Compilation(contentHash, warnings)` still works (the new `report` field defaults to `null`).

### Tests

10 new tests (`CompilationPipelineTest`):
- 1 happy path (Urban One spec compiles cleanly)
- 1 determinism (same spec → same content hash)
- 2 cross-version determinism (different catalog + compiler versions → different content hashes)
- 1 failed validation (COUPE + 4 doors → blocked report)
- 1 18-step structure (every step number is in 1..18)
- 2 report structure (clean report has 0 errors + 0 warnings; SOFT-violating report has 0 errors + ≥1 warning)
- 1 report validation (out-of-range step number → IllegalArgumentException)
- 1 Phase 1 backward compat (the `Compilation` data class still works with no report)

---

## The 18-step pipeline (Phase 2 / I-2.6 status)

```
Step  1: Lexing / structured decoding  (parser - Phase F2 second half)
Step  2: Parsing                        (parser - Phase F2 second half)
Step  3: Schema validation              (validator - Phase F2 third half) ✓
Step  4: Unit normalization             (placeholder)
Step  5: Alias resolution               (placeholder)
Step  6: Applicability resolution       (placeholder)
Step  7: Dependency expansion           (placeholder)
Step  8: Compatibility checking         (future - Phase F2 fifth half)
Step  9: Constraint solving             (placeholder)
Step 10: Part selection                 (placeholder)
Step 11: Assembly graph construction    (placeholder)
Step 12: Interface binding              (placeholder)
Step 13: Collision / packaging checks   (placeholder)
Step 14: Diagnostic binding             (placeholder)
Step 15: BOM generation                 (placeholder)
Step 16: Scene-manifest generation      (placeholder)
Step 17: Compilation report             (CompilationReport) ✓
Step 18: Artifact hashing               (SHA-256 over canonical form) ✓
```

Three steps are implemented in Phase 2 / I-2.6:
- Step 3 (validator) — the cross-aggregate invariant check
- Step 17 (report) — the per-step aggregation + the diagnostic list
- Step 18 (hash) — the SHA-256 of the canonical form

The remaining 13 steps are placeholders. The future phases replace them with real implementations.

---

## Test counts

| Suite | Before | After | Delta |
|-------|--------|-------|-------|
| `CompilationPipelineTest` | 0 | 10 | +10 (new) |
| **Total JVM unit tests** | 2366 | 2376 | **+10** |

**0 lint warnings, 0 test failures, 0 build errors.**

---

## Files

### New (production)
- `app/src/main/java/com/elysium/vanguard/foundry/core/compiler/CompilationReport.kt`
- `app/src/main/java/com/elysium/vanguard/foundry/core/compiler/CompilationPipeline.kt`

### New (test)
- `app/src/test/java/com/elysium/vanguard/foundry/core/compiler/CompilationPipelineTest.kt`

### Modified (production)
- `app/src/main/java/com/elysium/vanguard/foundry/core/compiler/Compilation.kt` — added optional `report: CompilationReport?` field (backward compatible)

---

## Architectural notes

### Why the pipeline is a separate class (not a method on the validator)

The pipeline is a **compositional orchestrator** (it composes the validator + the canonical-form builder + the hash + the report). A method on the validator would couple the validator to the canonical form + the hash + the report — the validator is a single concern, the pipeline is a composition of concerns.

The pipeline's only dependency is the `SpecValidator` (the validator is a `SpecValidator` interface; the pipeline is testable with a hand-rolled validator).

### Why the report is nullable on `Compilation`

The Phase 1 `DeterministicVehicleCompiler` doesn't run the 18-step pipeline; it just hashes the canonical form. The Phase 1 `Compilation` has no report. The Phase 2 `CompilationPipeline` runs the 18-step pipeline + populates the report.

Making the `report` field nullable + defaulting to `null` keeps the Phase 1 API working. The Phase 2 callers check `compilation.report != null` to know if the pipeline ran.

### Why the per-step result is a sealed class

The step is either a `Success` or a `Failure`. A boolean flag (`success: Boolean`) would not let the `Failure` carry the typed diagnostic. A sealed class pattern-matches cleanly + the consumer iterates the steps to render the report.

### Why the validator is pluggable

The pipeline takes a `SpecValidator` (the interface), not a `DefaultSpecValidator` (the concrete class). A custom validator (an OEM-specific rule set) can be substituted. The pipeline is testable with a hand-rolled validator (e.g. a no-op validator that always returns valid).

---

## Next phases (the pipeline forward)

- **Phase F2 fifth half (I-2.5)** — Compatibility rules (the constraint engine + per-pair compatibility). The constraint engine is the next big chunk of step 8.
- **Phase F2 sixth (I-2.8)** — Editor support (the live-validation surface that consumes the validator's diagnostics + the pipeline's report).
- **Phase 3 (G4)** — 3D pipeline (Scene manifest + LODs + asset validation). The 3D pipeline consumes the `Compilation` (steps 15, 16).
- **Phase 4 (G5)** — AI council (typed `AIProposal` + multi-agent deliberation). The AI council's `AIProposal<DslMutation>` references the `CompiledVehicleSpec` (the pipeline's input).

The pipeline is the **canonical entry point** for "compile a parsed spec". Every downstream surface (3D, marketplace, AI council) consumes the `Compilation` (or a richer product of the pipeline's later steps).
