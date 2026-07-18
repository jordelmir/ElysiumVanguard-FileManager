package com.elysium.vanguard.foundry.core.dsl.parser

import com.elysium.vanguard.foundry.core.dsl.schema.CompiledVehicleSpec

/**
 * The Vehicle Spec parser — the boundary between the text
 * surface (JSON / YAML / visual AST) and the typed schema
 * (`CompiledVehicleSpec`).
 *
 * Per `.ai/skills/04-vehicle-dsl-compiler/SKILL.md` section 6
 * step 2 (Parsing) + step 3 (Schema validation) + section 17
 * (Quality gates):
 *   - The parser is **total**: every valid input is parsed;
 *     an invalid input is rejected with a typed
 *     `CompilationDiagnostic`.
 *   - The parser is **deterministic**: the same input
 *     produces the same `CompiledVehicleSpec` (the
 *     integration test asserts this).
 *   - The parser does not execute user-supplied code (per
 *     skill 04 section 25 — security).
 *   - The parser emits **all** diagnostics it can detect in a
 *     single pass (the user gets a complete report, not a
 *     whack-a-mole of one error at a time).
 *
 * The parser returns `Result<CompiledVehicleSpec>` (success)
 * or `Result.failure(CompilationDiagnostic)` (single
 * diagnostic) / a list of diagnostics via
 * `parseWithDiagnostics`. The latter is the production
 * surface — the compiler pipeline (skill 04 section 6 steps
 * 4-18) collects every diagnostic in one pass and emits a
 * single `CompilationReport`.
 */
interface VehicleSpecParser {
    /**
     * Parse the text + return a `Result<CompiledVehicleSpec>`.
     * The first diagnostic encountered is returned as the
     * failure value. For a complete report, use
     * `parseWithDiagnostics`.
     */
    fun parse(text: String): Result<CompiledVehicleSpec>

    /**
     * Parse the text + return all diagnostics encountered.
     * The success case is `Result.success(spec)` with an
     * empty diagnostic list. The failure case is
     * `Result.success(spec)` with a non-empty diagnostic list
     * (the spec is "best effort" — the consumer decides
     * whether to accept a soft-warning spec or reject).
     *
     * The return type is intentionally a typed value, not
     * a `Map<String, Any>`. The consumer pattern-matches on
     * the variant.
     */
    fun parseWithDiagnostics(text: String): ParseResult
}

/**
 * The result of a `parseWithDiagnostics` call. A successful
 * parse has an empty diagnostic list; a failed parse has a
 * non-empty list. The spec is the "best effort" output — a
 * spec that the parser was able to assemble despite the
 * diagnostics.
 */
sealed class ParseResult {
    /**
     * The parse succeeded. The spec is the typed output; the
     * diagnostics list may be empty (a clean parse) or
     * non-empty (a parse with `SOFT` warnings).
     */
    data class Success(
        val spec: CompiledVehicleSpec,
        val diagnostics: List<CompilationDiagnostic>,
    ) : ParseResult()

    /**
     * The parse failed catastrophically (e.g. malformed JSON,
     * a required field at the root is missing). The spec is
     * `null`; the diagnostics list is non-empty.
     */
    data class Failure(
        val diagnostics: List<CompilationDiagnostic>,
    ) : ParseResult() {
        init {
            require(diagnostics.isNotEmpty()) {
                "ParseResult.Failure must have at least one diagnostic"
            }
        }
    }
}
