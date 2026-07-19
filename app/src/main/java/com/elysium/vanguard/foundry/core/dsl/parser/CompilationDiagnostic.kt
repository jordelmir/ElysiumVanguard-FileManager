package com.elysium.vanguard.foundry.core.dsl.parser

import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError

/**
 * The typed diagnostic envelope for the Vehicle Spec compiler.
 *
 * Per `.ai/skills/04-vehicle-dsl-compiler/SKILL.md` section 14
 * (Compilation report) + section 22 (Constraint severity) +
 * section 23 (typed error envelope):
 *   - A free-form string is never the value of an error.
 *   - A `Map<String, Any>` is never the value of an error.
 *   - Every diagnostic has a `code` + a `severity` + a typed
 *     payload + the path(s) in the spec that the violation
 *     refers to.
 *   - The user-facing `message` is in the user's locale
 *     (per skill 11's i18n bundle) — the parser emits the
 *     canonical English message; the UI translates.
 *
 * The diagnostic is a sealed class; the consumer pattern-
 * matches on the variant. The diagnostic is also a
 * `FoundryError` (per `.ai/AGENTS.md` 24.1 + the
 * `FoundryError` envelope): the diagnostic IS the typed
 * error; the consumer does not need to re-validate.
 *
 * The diagnostic is **path-aware**: every diagnostic carries
 * the JSON path (e.g. `$.body.architecture`) so the UI can
 * navigate the user to the offending field.
 */
sealed class CompilationDiagnostic(
    message: String,
    val code: String,
    val severity: Severity,
    val paths: List<String>,
) : RuntimeException(message) {

    /**
     * The severity of the diagnostic (per skill 04 section 22).
     * The default is `HARD` (a missing severity is treated as
     * a compile error).
     */
    enum class Severity {
        /** Compilation fails. The `Spec.Artifact` is not emitted. */
        HARD,

        /**
         * Compilation fails AND a `SafetyFinding` is filed in
         * the audit trail. The spec is blocked until a human
         * engineer reviews the finding + signs off.
         */
        SAFETY_CRITICAL,

        /**
         * Compilation is blocked for the affected market. The
         * spec is emitted for other markets.
         */
        REGULATORY,

        /**
         * Compilation succeeds. The `CompilationReport` includes
         * a warning. The user is informed; the spec is usable.
         */
        SOFT,

        /**
         * The candidate is ranked but not rejected. The user
         * picks from the ranked candidates.
         */
        OPTIMIZATION,
    }

    /**
     * The JSON input was not valid JSON. A malformed brace +
     * a missing comma + a string that lacks a closing quote.
     * The diagnostic is at the root of the input; no `path`
     * is meaningful.
     */
    data class SyntaxError(
        val reason: String,
    ) : CompilationDiagnostic(
        message = "Syntax error: $reason",
        code = "VCOMP-SYNTAX-001",
        severity = Severity.HARD,
        paths = emptyList(),
    )

    /**
     * A required field is missing from the input.
     */
    data class MissingRequiredField(
        val field: String,
        val path: String,
    ) : CompilationDiagnostic(
        message = "Missing required field '$field' at $path",
        code = "VCOMP-SCHEMA-002",
        severity = Severity.HARD,
        paths = listOf(path),
    )

    /**
     * A field has the wrong JSON type (e.g. a string where an
     * integer is expected, an array where an object is
     * expected).
     */
    data class WrongType(
        val field: String,
        val path: String,
        val expected: String,
        val actual: String,
    ) : CompilationDiagnostic(
        message = "Wrong type at $path: expected $expected, got $actual",
        code = "VCOMP-SCHEMA-003",
        severity = Severity.HARD,
        paths = listOf(path),
    )

    /**
     * A field has a value that is not in the allowed enum
     * set (e.g. `body.architecture = "AIRCRAFT"` is not a
     * `BodyArchitecture`).
     */
    data class UnknownEnumValue(
        val field: String,
        val path: String,
        val allowed: List<String>,
        val actual: String,
    ) : CompilationDiagnostic(
        message = "Unknown enum value '$actual' at $path; allowed: $allowed",
        code = "VCOMP-SCHEMA-004",
        severity = Severity.HARD,
        paths = listOf(path),
    )

    /**
     * A `UnitValue` field has a value that is NaN or
     * ±Infinity (per skill 04 section 25 — security).
     */
    data class InvalidUnitValue(
        val path: String,
        val rawValue: String,
    ) : CompilationDiagnostic(
        message = "Invalid unit value '$rawValue' at $path; " +
            "value must be finite (no NaN, no Infinity)",
        code = "VCOMP-VALUE-005",
        severity = Severity.HARD,
        paths = listOf(path),
    )

    /**
     * A `UnitValue` field has a unit that is not in the
     * allowed unit set (e.g. `wheelbase.unit = "FURLONG"` is
     * not a `LengthUnit`).
     */
    data class UnknownUnit(
        val path: String,
        val allowed: List<String>,
        val actual: String,
    ) : CompilationDiagnostic(
        message = "Unknown unit '$actual' at $path; allowed: $allowed",
        code = "VCOMP-VALUE-006",
        severity = Severity.HARD,
        paths = listOf(path),
    )

    /**
     * A field has a value that violates a sub-aggregate
     * invariant (e.g. `body.doors = 7` is not in `{2, 3, 4,
     * 5}`). The reason is the typed init-block message.
     */
    data class InvariantViolation(
        val field: String,
        val path: String,
        val reason: String,
    ) : CompilationDiagnostic(
        message = "Invariant violation at $path: $reason",
        code = "VCOMP-INVARIANT-007",
        severity = Severity.HARD,
        paths = listOf(path),
    )

    /**
     * A spec has a value combination that violates a
     * **cross-aggregate invariant** — the validator
     * (Phase 2 / I-2.4) catches what the data-class
     * `init` blocks don't (e.g. `ELECTRIC` +
     * `INLINE_4`, `V12` + `FWD`, `COUPE` + 4 doors).
     *
     * The `ruleCode` is the stable identifier of
     * the [com.elysium.vanguard.foundry.core.dsl.validator.SpecRule]
     * that fired; the `paths` are the JSON paths
     * the rule references; the `reason` is the
     * human-readable explanation.
     *
     * Phase 2 / I-2.4 — new variant.
     */
    data class CrossAggregateInvariantViolation(
        val ruleCode: String,
        val reason: String,
        val jsonPaths: List<String>,
        val diagnosticSeverity: Severity = Severity.HARD,
    ) : CompilationDiagnostic(
        message = "Cross-aggregate invariant violation " +
            "($ruleCode): $reason",
        code = "VCOMP-CROSS-008",
        severity = diagnosticSeverity,
        paths = jsonPaths,
    )

    /**
     * Convert the diagnostic to a `FoundryError` envelope.
     * The `code` is preserved; the `message` is the diagnostic
     * message; the `retryClassification` is `NON_RETRYABLE`
     * (per `.ai/AGENTS.md` 24.4 — a syntax / schema error is
     * the user's mistake; retrying without a fix is futile).
     */
    fun toFoundryError(): FoundryError = FoundryError.VehicleDefinitionInvalid(
        field = paths.firstOrNull() ?: code,
        reason = "$code: $message",
    )
}
