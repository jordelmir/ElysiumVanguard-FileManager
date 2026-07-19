package com.elysium.vanguard.foundry.core.dsl.editor

import com.elysium.vanguard.foundry.core.dsl.compatibility.CompatibilityConstraintEngine
import com.elysium.vanguard.foundry.core.dsl.parser.CompilationDiagnostic
import com.elysium.vanguard.foundry.core.dsl.schema.CompiledVehicleSpec
import com.elysium.vanguard.foundry.core.dsl.validator.SpecValidator
import com.elysium.vanguard.foundry.core.dsl.validator.ValidationResult

/**
 * Phase 2 / I-2.8 — the **Live Spec Validator**.
 *
 * The live validator is the editor's "validate on
 * every keystroke" surface (per `.ai/skills/04-
 * vehicle-dsl-compiler/SKILL.md` section 10).
 *
 * The live validator composes:
 *   - The [SpecValidator] (Phase F2 / I-2.4) — the
 *     invariant checker.
 *   - The [CompatibilityConstraintEngine] (Phase F2 /
 *     I-2.5) — the constraint engine.
 *   - A [SourceMap] (this phase) — the source
 *     positions of every field.
 *
 * The live validator returns a [LiveValidationResult]
 * that contains:
 *   - The aggregated diagnostics (validator +
 *     constraint engine).
 *   - The [DiagnosticAnnotation]s (the editor's
 *     source-position-annotated diagnostics).
 *   - The [SourceMap] (for go-to-definition +
 *     hover docs).
 *   - A `isValid` flag (no HARD / SAFETY_CRITICAL
 *     / REGULATORY diagnostic).
 *
 * The live validator is **fast** (microseconds per
 * spec — the validator + the constraint engine
 * are both pure-domain, no I/O, no Android
 * dependencies). The editor runs the validator
 * on every keystroke.
 *
 * The live validator is **deterministic** (same
 * spec + same source map → same result).
 */
class LiveSpecValidator(
    private val validator: SpecValidator,
    private val constraintEngine: CompatibilityConstraintEngine,
) {

    /**
     * Run the live validation. Returns a
     * [LiveValidationResult] with the
     * aggregated diagnostics + annotations +
     * source map.
     */
    fun validate(
        spec: CompiledVehicleSpec,
        sourceMap: SourceMap,
    ): LiveValidationResult {
        // Run the validator (Phase F2 / I-2.4).
        val validationResult: ValidationResult = validator.validate(spec)
        // Run the constraint engine (Phase F2 / I-2.5).
        val constraintDiagnostics: List<CompilationDiagnostic> =
            constraintEngine.evaluate(spec)
        // Aggregate the diagnostics.
        val allDiagnostics: List<CompilationDiagnostic> =
            validationResult.diagnostics + constraintDiagnostics
        // Build the live validation result.
        val liveResult = LiveValidationResult(
            diagnostics = allDiagnostics,
            sourceMap = sourceMap,
        )
        return liveResult
    }
}

/**
 * The result of a live validation pass.
 *
 * The result has:
 *   - `diagnostics: List<CompilationDiagnostic>` —
 *     the aggregated diagnostics (validator +
 *     constraint engine).
 *   - `annotations: List<DiagnosticAnnotation>` —
 *     the source-position-annotated diagnostics
 *     the editor renders.
 *   - `sourceMap: SourceMap` — the source map
 *     (for go-to-definition + hover docs).
 *   - `isValid: Boolean` — no blocking
 *     diagnostic.
 */
data class LiveValidationResult(
    val diagnostics: List<CompilationDiagnostic>,
    val sourceMap: SourceMap,
) {
    /**
     * The annotations the editor renders. The
     * list is sorted by (line, column) for
     * deterministic display.
     */
    val annotations: List<DiagnosticAnnotation> = run {
        val all = diagnostics.flatMap { it.toAnnotations(sourceMap) }
        all.sortedWith(
            compareBy(
                { it.position.line },
                { it.position.column },
                { it.code },
            ),
        )
    }

    /**
     * The blocking diagnostics (HARD /
     * SAFETY_CRITICAL / REGULATORY). The
     * spec is invalid when at least one
     * blocking diagnostic is present.
     */
    val blockingDiagnostics: List<CompilationDiagnostic> = diagnostics.filter {
        it.severity == CompilationDiagnostic.Severity.HARD ||
            it.severity == CompilationDiagnostic.Severity.SAFETY_CRITICAL ||
            it.severity == CompilationDiagnostic.Severity.REGULATORY
    }

    /**
     * The warnings (SOFT diagnostics).
     */
    val warnings: List<CompilationDiagnostic> = diagnostics.filter {
        it.severity == CompilationDiagnostic.Severity.SOFT
    }

    /**
     * The optimization suggestions.
     */
    val optimizations: List<CompilationDiagnostic> = diagnostics.filter {
        it.severity == CompilationDiagnostic.Severity.OPTIMIZATION
    }

    /**
     * The spec is valid when no blocking
     * diagnostic is present.
     */
    val isValid: Boolean get() = blockingDiagnostics.isEmpty()
}
