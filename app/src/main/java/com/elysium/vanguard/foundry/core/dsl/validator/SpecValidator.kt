package com.elysium.vanguard.foundry.core.dsl.validator

import com.elysium.vanguard.foundry.core.dsl.parser.CompilationDiagnostic
import com.elysium.vanguard.foundry.core.dsl.schema.CompiledVehicleSpec

/**
 * Phase 2 / I-2.4 — the **Spec Validator** contract.
 *
 * The validator is the cross-aggregate invariant checker
 * (per `.ai/skills/04-vehicle-dsl-compiler/SKILL.md` section
 * 6 step 3 — Schema validation, plus the per-step
 * invariant checks that the data-class `init` blocks do
 * not cover).
 *
 * The validator is:
 *   - **Total.** Every `CompiledVehicleSpec` is checked; no
 *     spec is rejected without a typed diagnostic.
 *   - **Deterministic.** The same spec produces the same
 *     diagnostic list (rule order is stable; rule
 *     evaluation is order-independent).
 *   - **Pure-domain.** No I/O, no Android dependencies.
 *   - **Pluggable.** A [SpecRule] is a discrete invariant
 *     check; the validator composes a [SpecRuleSet] of
 *     rules and applies them in order.
 *
 * The validator returns a [ValidationResult] that records
 * the diagnostics + a `boolean isValid` shortcut. The
 * consumer pattern-matches on the severity (a `HARD` or
 * `SAFETY_CRITICAL` or `REGULATORY` diagnostic makes the
 * spec invalid; a `SOFT` or `OPTIMIZATION` diagnostic is
 * a warning).
 *
 * The validator is **separate from the parser's
 * schema-validation** (Phase F2). The parser's checks
 * are JSON-shape checks (the right field names, the
 * right types, the right enum values). The validator's
 * checks are cross-aggregate semantic checks (the
 * `ELECTRIC` + `INLINE_4` combination, the
 * `V12` + `FWD` combination, etc.).
 */
interface SpecValidator {

    /**
     * Validate a spec. Returns a [ValidationResult]
     * with the full diagnostic list (errors + warnings
     * + info notes) + a validity flag.
     */
    fun validate(spec: CompiledVehicleSpec): ValidationResult
}

/**
 * The result of a validation pass.
 *
 * `diagnostics` is the full list (the consumer filters
 * by severity). `isValid` is `true` when no
 * `HARD` / `SAFETY_CRITICAL` / `REGULATORY` diagnostic
 * is present.
 */
data class ValidationResult(
    val diagnostics: List<CompilationDiagnostic>,
) {
    /**
     * The spec is valid when no `HARD`,
     * `SAFETY_CRITICAL`, or `REGULATORY` diagnostic
     * is present. `SOFT` and `OPTIMIZATION` diagnostics
     * are warnings — they don't make the spec invalid.
     */
    val isValid: Boolean = diagnostics.none {
        it.severity == CompilationDiagnostic.Severity.HARD ||
            it.severity == CompilationDiagnostic.Severity.SAFETY_CRITICAL ||
            it.severity == CompilationDiagnostic.Severity.REGULATORY
    }

    /**
     * The hard + safety-critical + regulatory errors
     * (the diagnostics that block compilation).
     */
    val blockingDiagnostics: List<CompilationDiagnostic> = diagnostics.filter {
        it.severity == CompilationDiagnostic.Severity.HARD ||
            it.severity == CompilationDiagnostic.Severity.SAFETY_CRITICAL ||
            it.severity == CompilationDiagnostic.Severity.REGULATORY
    }

    /**
     * The soft warnings (the diagnostics that flag a
     * spec without blocking it).
     */
    val warnings: List<CompilationDiagnostic> = diagnostics.filter {
        it.severity == CompilationDiagnostic.Severity.SOFT
    }

    /**
     * The optimization candidates (the diagnostics
     * that rank alternative choices).
     */
    val optimizations: List<CompilationDiagnostic> = diagnostics.filter {
        it.severity == CompilationDiagnostic.Severity.OPTIMIZATION
    }
}
