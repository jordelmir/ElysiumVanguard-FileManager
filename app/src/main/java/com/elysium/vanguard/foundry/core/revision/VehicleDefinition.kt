package com.elysium.vanguard.foundry.core.revision

import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError

/**
 * The user's intent for a vehicle. In Phase 1 this is a structured
 * data class with a `parameters` map; in Phase 2 the platform will
 * add a DSL parser (per `.ai/skills/04-vehicle-dsl-compiler/SKILL.md`)
 * that produces a `VehicleDefinition` from a text source.
 *
 * Determinism rule (per `.ai/STANDARDS.md` 2.2 + the integration
 * test contract): the canonical form of the definition MUST be
 * stable across JVMs, OSes, and Kotlin versions. The
 * `canonicalForm()` method sorts the `parameters` map and emits
 * a deterministic UTF-8 string; the compiler hashes this string
 * to produce the `Compilation.contentHash`.
 */
data class VehicleDefinition(
    val projectId: ProjectId,
    val name: String,
    val parameters: Map<String, String>,
) {
    // No `init` block: the constructor is permissive (so that error-path
    // tests can construct invalid definitions and assert on the typed
    // error). The full validation lives in `validate()`.

    /**
     * Produce a canonical UTF-8 byte sequence for the definition. The
     * sequence is stable across JVMs, OSes, and Kotlin versions:
     *   - The projectId is the lowercase UUID.
     *   - The name is the trimmed string.
     *   - The parameters are sorted by key.
     *   - The format is `key=value;key=value`.
     *
     * Same `(projectId, name, parameters)` -> same canonical form
     * -> same SHA-256 digest -> same `Compilation.contentHash`.
     */
    fun canonicalForm(): String {
        val sortedKeys = parameters.keys.sorted()
        val params = sortedKeys.joinToString(separator = ";") { key ->
            "$key=${parameters[key]}"
        }
        return "definition:v1|projectId=${projectId.value}|name=${name.trim()}|params=$params"
    }

    /**
     * Validate the definition. Phase 1 covers the structural
     * invariants (non-blank, non-empty parameters); Phase 2 adds the
     * type-level + cross-reference validation from the DSL compiler.
     */
    fun validate(): Result<Unit> {
        if (name.isBlank()) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "VehicleDefinition.name",
                    reason = "name must not be blank",
                ),
            )
        }
        if (parameters.isEmpty()) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "VehicleDefinition.parameters",
                    reason = "parameters must not be empty",
                ),
            )
        }
        return Result.success(Unit)
    }
}
