package com.elysium.vanguard.foundry.core.dsl.validator

import com.elysium.vanguard.foundry.core.dsl.schema.CompiledVehicleSpec
import com.elysium.vanguard.foundry.core.dsl.parser.CompilationDiagnostic

/**
 * Phase 2 / I-2.4 — the default [SpecValidator]
 * implementation.
 *
 * The validator applies a [SpecRuleSet] of rules in
 * order and aggregates the diagnostics. The order is
 * **stable** (the rule set's `rules` list is iterated
 * index-first) so the same spec produces the same
 * diagnostic list.
 *
 * The default rule set is the
 * [com.elysium.vanguard.foundry.core.dsl.validator.rules.DefaultRuleSet]
 * — the 9 cross-aggregate invariants the platform
 * ships. Custom validators can substitute their own
 * rule sets (e.g. an OEM-specific validator that adds
 * a "this OEM does not produce V12 engines" rule).
 *
 * The validator is **pure-domain**: no I/O, no Android
 * dependencies, no Hilt. The validator is JVM-testable
 * end-to-end (the unit tests cover every rule).
 */
class DefaultSpecValidator(
    private val ruleSet: SpecRuleSet,
) : SpecValidator {

    /**
     * Apply every rule in the rule set. The
     * diagnostics are concatenated in rule order.
     * The `ValidationResult.isValid` is `true` when
     * no HARD / SAFETY_CRITICAL / REGULATORY
     * diagnostic is present.
     */
    override fun validate(spec: CompiledVehicleSpec): ValidationResult {
        val diagnostics = ruleSet.rules.flatMap { rule ->
            rule.check(spec)
        }
        return ValidationResult(diagnostics = diagnostics)
    }

    companion object {
        /**
         * Build a validator with the default rule
         * set. The default rule set is the cross-
         * aggregate invariants the platform ships;
         * the [com.elysium.vanguard.foundry.core.dsl.validator.rules]
         * sub-package contains the rule
         * implementations.
         */
        fun withDefaultRules(): DefaultSpecValidator = DefaultSpecValidator(
            ruleSet = com.elysium.vanguard.foundry.core.dsl.validator.rules.DefaultRuleSet,
        )
    }
}

/**
 * A named collection of [SpecRule]s. The rule set is
 * the **input** to a [SpecValidator]; the rule set is
 * a value object (the same rule set can be reused
 * across validators).
 */
data class SpecRuleSet(
    val name: String,
    val version: String,
    val rules: List<SpecRule>,
) {
    init {
        require(name.isNotBlank()) { "SpecRuleSet.name must not be blank" }
        require(version.isNotBlank()) { "SpecRuleSet.version must not be blank" }
        // The rule codes MUST be unique within a
        // rule set. A duplicate code is a smell
        // (two rules emit the same diagnostic code;
        // the consumer cannot tell which rule
        // fired).
        val codes = rules.map { it.code }
        require(codes.size == codes.toSet().size) {
            "SpecRuleSet has duplicate rule codes: " +
                codes.groupBy { it }.filterValues { it.size > 1 }.keys
        }
    }
}
