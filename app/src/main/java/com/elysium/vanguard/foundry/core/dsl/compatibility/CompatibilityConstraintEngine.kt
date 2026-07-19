package com.elysium.vanguard.foundry.core.dsl.compatibility

import com.elysium.vanguard.foundry.core.dsl.parser.CompilationDiagnostic
import com.elysium.vanguard.foundry.core.dsl.schema.CompiledVehicleSpec

/**
 * Phase 2 / I-2.5 — the **Compatibility Constraint Engine**.
 *
 * The engine is the step 8 of the 18-step pipeline
 * (per `.ai/skills/04-vehicle-dsl-compiler/SKILL.md`
 * section 6). The engine evaluates the
 * `CompatibilityConstraint`s (per skill 03 section
 * 13.4) against the spec + returns a list of typed
 * diagnostics.
 *
 * The engine is:
 *   - **Declarative** — a constraint is a typed
 *     expression; the engine evaluates the expression.
 *   - **Deterministic** — a constraint that evaluates
 *     to `true` on the same input always evaluates to
 *     `true`.
 *   - **Typed** — a constraint violation is a typed
 *     [CompilationDiagnostic] (per `.ai/STANDARDS.md`
 *     section 7).
 *
 * The engine is distinct from the **validator**
 * (Phase 2 / I-2.4):
 *   - The validator checks **invariant** combinations
 *     (e.g. `ELECTRIC` + `INLINE_4` — mechanically
 *     impossible).
 *   - The engine checks **constraint** combinations
 *     (e.g. "an SUV needs wheelbase >= 2.5m" — a
 *     market / regulatory standard, not a physical
 *     law).
 *
 * The engine's severities are typically:
 *   - **REGULATORY** (a market standard, e.g. EU
 *     pedestrian safety) — blocks compilation for
 *     that market.
 *   - **OPTIMIZATION** (a better combination exists) —
 *     a ranked candidate, not a block.
 *   - **SOFT** (a market preference, e.g. "Japanese
 *     kei cars have displacement < 660cc") — a
 *     warning.
 *
 * The engine is **pure-domain**: no I/O, no Android
 * dependencies. The engine is JVM-testable end-to-end.
 */
interface CompatibilityConstraintEngine {

    /**
     * Evaluate the constraint set against the spec.
     * Returns a list of typed diagnostics (the
     * diagnostics are the per-constraint violations;
     * a passing constraint returns an empty list).
     */
    fun evaluate(spec: CompiledVehicleSpec): List<CompilationDiagnostic>
}

/**
 * A single compatibility constraint.
 *
 * A [CompatibilityConstraint] is a typed invariant
 * check that the [CompatibilityConstraintEngine]
 * composes. The constraint has:
 *   - A stable `code` (the `VCOMP-CONSTRAINT-XXX-YYY`
 *     identifier the diagnostic carries).
 *   - A human-readable `name`.
 *   - A `check(spec)` function that returns a list of
 *     typed diagnostics (a passing constraint returns
 *     an empty list; a failing constraint returns one
 *     or more diagnostics).
 *
 * The constraint is **pure**: it does not mutate the
 * spec, it does not have side effects. The constraint
 * is **deterministic**: the same spec produces the
 * same diagnostic list.
 */
interface CompatibilityConstraint {

    /**
     * The constraint's stable identifier. Renaming
     * a constraint is a breaking change.
     */
    val code: String

    /**
     * The constraint's human-readable name (used in
     * the editor's "live validation" panel + in the
     * compilation report).
     */
    val name: String

    /**
     * Apply the constraint to the spec. Returns an
     * empty list when the spec passes; returns one or
     * more [CompilationDiagnostic]s when the spec
     * fails.
     */
    fun check(spec: CompiledVehicleSpec): List<CompilationDiagnostic>
}
