package com.elysium.vanguard.foundry.core.compiler

import com.elysium.vanguard.foundry.core.ontology.primitives.CatalogRevision
import com.elysium.vanguard.foundry.core.ontology.primitives.CompilerVersion
import com.elysium.vanguard.foundry.core.revision.VehicleDefinition

/**
 * The interface every deterministic vehicle compiler must implement.
 *
 * Contract (per `.ai/STANDARDS.md` 2.2 + `.ai/skills/04-vehicle-dsl-
 * compiler/SKILL.md` section 8):
 *   - The compiler is **deterministic**: the same `(definition,
 *     catalogRevision, compilerVersion)` produces the same
 *     `Compilation.contentHash` byte-for-byte across JVMs, OSes,
 *     and Kotlin versions.
 *   - The compiler is **total**: every well-formed `VehicleDefinition`
 *     compiles to a `Compilation`; an ill-formed definition produces
 *     a typed `CompilationNonDeterministic` error.
 *   - The compiler is **idempotent**: a second `compile` call with
 *     the same inputs returns a `Compilation` with the same
 *     `contentHash` (the integration test asserts this).
 *
 * Phase 1 ships the `DeterministicVehicleCompiler` implementation
 * (a thin wrapper over SHA-256 + the canonical form). Phase 2
 * adds the full 18-step pipeline (per skill 04 section 8) — the
 * parser + the resolver + the type-checker + the constraint
 * validator + the manifest generator.
 */
fun interface VehicleCompiler {
    fun compile(
        definition: VehicleDefinition,
        catalogRevision: CatalogRevision,
        compilerVersion: CompilerVersion,
    ): Result<Compilation>
}
