package com.elysium.vanguard.foundry.core.dsl.validator

import com.elysium.vanguard.foundry.core.dsl.parser.CompilationDiagnostic
import com.elysium.vanguard.foundry.core.dsl.schema.CompiledVehicleSpec

/**
 * Phase 2 / I-2.4 — a single spec rule.
 *
 * A [SpecRule] is a **discrete, named** invariant check
 * that the [SpecValidator] composes. The rule is
 * deterministic + total (every spec is checked; a
 * passing rule returns an empty list, a failing rule
 * returns one or more typed diagnostics).
 *
 * Rules are organized in the [rules] sub-package; the
 * default rule set (the [DefaultRuleSet]) lists every
 * rule the platform ships. Future versions of the
 * platform (per `.ai/skills/04-vehicle-dsl-compiler/
 * SKILL.md` section 6 step 22 — Rule-set versioning)
 * can add or remove rules without breaking existing
 * specs (the diagnostic's `code` is the rule's stable
 * identifier; the consumer can ignore unknown codes).
 */
interface SpecRule {

    /**
     * The rule's stable identifier (the `code`
     * prefix the rule emits on a diagnostic). The
     * identifier is the rule's API contract; renaming
     * a rule is a breaking change.
     */
    val code: String

    /**
     * The rule's human-readable name (used in the
     * editor's "live validation" panel + in the
     * compilation report).
     */
    val name: String

    /**
     * Apply the rule to the spec. Returns an empty
     * list when the spec passes; returns one or more
     * [CompilationDiagnostic]s when the spec fails.
     *
     * The rule is **pure**: it does not mutate the
     * spec, it does not have side effects. The rule
     * is **deterministic**: the same spec produces
     * the same diagnostic list.
     */
    fun check(spec: CompiledVehicleSpec): List<CompilationDiagnostic>
}
