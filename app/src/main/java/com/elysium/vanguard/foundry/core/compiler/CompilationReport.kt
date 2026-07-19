package com.elysium.vanguard.foundry.core.compiler

import com.elysium.vanguard.foundry.core.dsl.parser.CompilationDiagnostic
import com.elysium.vanguard.foundry.core.dsl.validator.ValidationResult

/**
 * Phase 2 / I-2.7 — the user-facing **Compilation Report**.
 *
 * The compilation report is the artifact the editor
 * shows after a compile. Per `.ai/skills/04-vehicle-
 * dsl-compiler/SKILL.md` section 14 (Compilation
 * report) + section 22 (Constraint severity):
 *
 *   - **Errors** (the per-step errors; the
 *     `Spec.Artifact` is NOT emitted when there is
 *     an error).
 *   - **Warnings** (the per-step warnings; the
 *     `Spec.Artifact` IS emitted but flagged).
 *   - **Info notes** (the per-step info; the
 *     `Spec.Artifact` is emitted).
 *
 * The report is a typed value: the consumer pattern-
 * matches on the per-step result variant. The report
 * is **immutable** (data class + no setters); a new
 * compilation produces a new report.
 *
 * The report is **path-aware**: every diagnostic
 * carries the JSON path (e.g. `$.body.architecture`)
 * so the UI can navigate the user to the offending
 * field.
 *
 * The report is **localized** (per skill 11's i18n
 * bundle). The `CompilationDiagnostic.message` is
 * the canonical English message; the UI translates.
 */
data class CompilationReport(
    /**
     * The per-step result. The result is a typed
     * value: a `Success` step has the canonical
     * output; a `Failure` step has the diagnostic.
     * The consumer iterates the steps to render
     * the report.
     */
    val steps: List<Step>,
    /**
     * The validator's diagnostics (the cross-
     * aggregate invariants). The diagnostics are
     * also referenced by the relevant
     * [Step]s; the field is a flat list for the
     * UI's "all diagnostics" view.
     */
    val validationDiagnostics: List<CompilationDiagnostic>,
    /**
     * The compilation is **blocked** when at least
     * one step is a `Failure` OR the validation
     * has a blocking diagnostic (HARD /
     * SAFETY_CRITICAL / REGULATORY).
     */
    val isBlocked: Boolean,
) {
    init {
        require(steps.all { it.stepNumber in 1..18 }) {
            "CompilationReport: every step's number must be in 1..18, " +
                "got ${steps.map { it.stepNumber }}"
        }
    }

    /**
     * The errors (the diagnostics that block
     * compilation). The list is the union of
     * the failure steps' diagnostics + the
     * validation's blocking diagnostics.
     */
    val errors: List<CompilationDiagnostic> by lazy {
        val fromSteps = steps.flatMap { step ->
            when (step) {
                is Step.Failure -> listOf(step.diagnostic)
                is Step.Success -> emptyList()
            }
        }
        val fromValidation = validationDiagnostics.filter {
            it.severity == CompilationDiagnostic.Severity.HARD ||
                it.severity == CompilationDiagnostic.Severity.SAFETY_CRITICAL ||
                it.severity == CompilationDiagnostic.Severity.REGULATORY
        }
        fromSteps + fromValidation
    }

    /**
     * The warnings (the diagnostics that flag a
     * spec without blocking it). The list is the
     * validation's SOFT diagnostics (the per-step
     * steps don't currently emit SOFT diagnostics).
     */
    val warnings: List<CompilationDiagnostic> by lazy {
        validationDiagnostics.filter {
            it.severity == CompilationDiagnostic.Severity.SOFT
        }
    }

    /**
     * The optimization candidates (the diagnostics
     * that rank alternative choices).
     */
    val optimizations: List<CompilationDiagnostic> by lazy {
        validationDiagnostics.filter {
            it.severity == CompilationDiagnostic.Severity.OPTIMIZATION
        }
    }

    /**
     * The simple warnings list — the `Compilation.warnings`
     * field is a `List<String>` (backward-compat with
     * Phase 1). The list is the messages of the
     * [warnings] + the [errors] (so the Phase 1
     * consumer sees every diagnostic).
     */
    val warningMessages: List<String> by lazy {
        (errors + warnings).map { it.message ?: "(no message)" }
    }

    /**
     * A per-step result. The 18-step pipeline
     * produces one [Step] per step. The consumer
     * pattern-matches on the variant.
     */
    sealed class Step {
        abstract val stepNumber: Int
        abstract val stepName: String

        /**
         * The step succeeded. The `output` is the
         * step's typed output (e.g. step 3
         * "Schema validation" has the
         * [ValidationResult] as output).
         */
        data class Success(
            override val stepNumber: Int,
            override val stepName: String,
            val output: String? = null,
        ) : Step()

        /**
         * The step failed. The `diagnostic` is the
         * typed error the step emitted.
         */
        data class Failure(
            override val stepNumber: Int,
            override val stepName: String,
            val diagnostic: CompilationDiagnostic,
        ) : Step()
    }
}

/**
 * Build a [CompilationReport] from a [ValidationResult]
 * + a list of step results. The helper is the
 * typical builder for the 18-step pipeline: the
 * pipeline's per-step results are accumulated +
 * the validator's diagnostics are aggregated.
 *
 * The function is pure: same inputs → same report.
 */
fun buildReport(
    steps: List<CompilationReport.Step>,
    validationResult: ValidationResult,
): CompilationReport {
    val isBlocked = steps.any { it is CompilationReport.Step.Failure } ||
        !validationResult.isValid
    return CompilationReport(
        steps = steps,
        validationDiagnostics = validationResult.diagnostics,
        isBlocked = isBlocked,
    )
}
