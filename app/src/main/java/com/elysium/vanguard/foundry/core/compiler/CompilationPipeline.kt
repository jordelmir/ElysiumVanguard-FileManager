package com.elysium.vanguard.foundry.core.compiler

import com.elysium.vanguard.foundry.core.dsl.parser.CompilationDiagnostic
import com.elysium.vanguard.foundry.core.dsl.schema.CompiledVehicleSpec
import com.elysium.vanguard.foundry.core.dsl.validator.SpecValidator
import com.elysium.vanguard.foundry.core.dsl.validator.ValidationResult
import com.elysium.vanguard.foundry.core.ontology.primitives.CatalogRevision
import com.elysium.vanguard.foundry.core.ontology.primitives.CompilerVersion
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError

/**
 * Phase 2 / I-2.6 — the **Deterministic Compiler Pipeline**.
 *
 * The pipeline is the 18-step compile process (per
 * `.ai/skills/04-vehicle-dsl-compiler/SKILL.md` section 6
 * + section 8 — Determinism). The pipeline operates on a
 * [CompiledVehicleSpec] (the parser's output) + a
 * [CatalogRevision] + a [CompilerVersion] + a
 * [SpecValidator] and produces a [Compilation] with the
 * content hash + a [CompilationReport].
 *
 * The pipeline is **deterministic** (per skill 04 section 7):
 * the same `(spec, catalogRevision, compilerVersion,
 * validator)` produces the same `Compilation.contentHash`
 * byte-for-byte across JVMs, OSes, and Kotlin versions.
 *
 * The pipeline is **pure-domain**: no I/O, no Android
 * dependencies, no Hilt. The pipeline is JVM-testable
 * end-to-end with a hand-rolled validator fixture.
 *
 * Phase 2 / I-2.6 implementation: the pipeline runs 3
 * steps:
 *
 *   - **Step 3 (Schema validation)** — runs the
 *     [SpecValidator]; the result is the
 *     [ValidationResult] + a typed step result.
 *   - **Step 17 (Compilation report)** — builds the
 *     [CompilationReport] from the per-step results.
 *   - **Step 18 (Artifact hashing)** — computes the
 *     SHA-256 of the spec's canonical form + the
 *     catalog + the compiler version.
 *
 * Steps 1-2 are the parser (Phase F2's second half).
 * Steps 4-16 are the resolver + type-checker +
 * constraint engine (future phases). The pipeline
 * currently runs **only the steps that have been
 * implemented**; the remaining steps are placeholders
 * in the report (they emit a `Success` step with a
 * "not yet implemented" note).
 */
class CompilationPipeline(
    private val validator: SpecValidator,
) {

    /**
     * Run the 18-step pipeline on a [spec].
     *
     * The function is **total**: every spec produces a
     * `Result<Compilation>`. A spec that fails validation
     * is still returned (the `Compilation` is emitted; the
     * `report` carries the blocking diagnostics). A spec
     * that fails the canonical-form build is a hard error
     * (a `CompilationNonDeterministic`).
     */
    fun compile(
        spec: CompiledVehicleSpec,
        catalogRevision: CatalogRevision,
        compilerVersion: CompilerVersion,
    ): Result<Compilation> {
        val steps = mutableListOf<CompilationReport.Step>()

        // Step 3: Schema validation. The validator
        // runs first; a failure here blocks
        // compilation but the report is still
        // emitted (the user sees the diagnostics).
        val validationResult = validator.validate(spec)
        steps += if (validationResult.isValid) {
            CompilationReport.Step.Success(
                stepNumber = STEP_VALIDATION,
                stepName = "Schema validation",
                output = "${validationResult.diagnostics.size} diagnostics (0 blocking)",
            )
        } else {
            CompilationReport.Step.Failure(
                stepNumber = STEP_VALIDATION,
                stepName = "Schema validation",
                diagnostic = CompilationDiagnostic.CrossAggregateInvariantViolation(
                    ruleCode = "VCOMP-PIPELINE-VALIDATION",
                    reason = "validation produced " +
                        "${validationResult.blockingDiagnostics.size} blocking diagnostics",
                    jsonPaths = validationResult.blockingDiagnostics.flatMap { it.paths }.distinct(),
                ),
            )
        }

        // Steps 4-16: future implementation. Each
        // step is recorded as a `Success` with a
        // "not yet implemented" note. The report
        // shows the user which steps the pipeline
        // actually ran.
        for (stepInfo in FUTURE_STEPS) {
            steps += CompilationReport.Step.Success(
                stepNumber = stepInfo.number,
                stepName = stepInfo.name,
                output = "not yet implemented (skipped)",
            )
        }

        // Step 17: Compilation report. We build the
        // report from the per-step results + the
        // validation diagnostics. The report is
        // computed even when the validation failed
        // (the user needs to see the diagnostics).
        val report = buildReport(
            steps = steps,
            validationResult = validationResult,
        )

        // Step 18: Artifact hashing. The content
        // hash is the SHA-256 of the spec's
        // canonical form + the catalog + the
        // compiler version. The hash is computed
        // even when the validation failed (a
        // failed spec still has a content address;
        // the address is the canonical id of the
        // attempted compilation).
        val canonical = buildString {
            append("compilation:v2")  // Phase 2 version
            append("|catalog=").append(catalogRevision.value)
            append("|compiler=").append(compilerVersion.value)
            append("|ruleset=").append(validator::class.java.simpleName)
            append("|").append(spec.canonicalForm())
        }
        val contentHash = try {
            ContentHash.of(canonical)
        } catch (e: Exception) {
            return Result.failure(
                FoundryError.CompilationNonDeterministic(
                    reason = "artifact hashing failed: ${e.message ?: e::class.java.simpleName}",
                ),
            )
        }

        return Result.success(
            Compilation(
                contentHash = contentHash,
                warnings = report.warningMessages,
                report = report,
            ),
        )
    }

    private companion object {
        const val STEP_VALIDATION = 3

        /**
         * Steps 4-16 of the 18-step pipeline. Each
         * step is recorded in the report as a
         * "not yet implemented" success. When the
         * future phases implement the step, the
         * record changes from a placeholder to
         * the real implementation.
         */
        val FUTURE_STEPS: List<StepInfo> = listOf(
            StepInfo(4, "Unit normalization"),
            StepInfo(5, "Alias resolution"),
            StepInfo(6, "Applicability resolution"),
            StepInfo(7, "Dependency expansion"),
            StepInfo(8, "Compatibility checking"),
            StepInfo(9, "Constraint solving"),
            StepInfo(10, "Part selection"),
            StepInfo(11, "Assembly graph construction"),
            StepInfo(12, "Interface binding"),
            StepInfo(13, "Collision and packaging prechecks"),
            StepInfo(14, "Diagnostic binding"),
            StepInfo(15, "BOM generation"),
            StepInfo(16, "Scene-manifest generation"),
            StepInfo(17, "Compilation report"),
        )

        data class StepInfo(val number: Int, val name: String)
    }
}
