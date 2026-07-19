package com.elysium.vanguard.foundry.core.compiler

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash

/**
 * The output of the deterministic vehicle compiler.
 *
 * Per `.ai/STANDARDS.md` 2.2 + `.ai/skills/04-vehicle-dsl-compiler/
 * SKILL.md` section 3.2 (`CompilationResult`):
 *   - The `contentHash` is the SHA-256 digest of the canonical form
 *     of `(definition, catalogRevision, compilerVersion)`.
 *   - The `warnings` list is the user-facing compilation diagnostic
 *     list (the `CompilationDiagnostic` in skill 04 maps to a list
 *     of strings in Phase 1; the typed diagnostics are added in
 *     Phase 2).
 *   - The compilation is content-addressed; the `contentHash` is
 *     the canonical id of the compilation.
 *
 * The compilation is **immutable** (data class + no setters); the
 * `VehicleRevision` is the persistent record of the compilation.
 *
 * Phase 2 / I-2.7 — the `report` field carries the detailed
 * [CompilationReport] when the 18-step pipeline ran. The field
 * is nullable for backward compatibility with the Phase 1
 * `DeterministicVehicleCompiler` (which doesn't run the
 * pipeline; it just hashes the canonical form).
 */
data class Compilation(
    val contentHash: ContentHash,
    val warnings: List<String> = emptyList(),
    /**
     * Phase 2 / I-2.7 — the detailed compilation report
     * (errors + warnings + info notes + per-step results).
     * `null` when the compiler didn't run the 18-step
     * pipeline (the Phase 1 SHA-256 stub).
     */
    val report: CompilationReport? = null,
)
