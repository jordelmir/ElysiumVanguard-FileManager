package com.elysium.vanguard.foundry.core.compiler

import com.elysium.vanguard.foundry.core.ontology.primitives.CatalogRevision
import com.elysium.vanguard.foundry.core.ontology.primitives.CompilerVersion
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.revision.VehicleDefinition

/**
 * Phase 1 implementation of the deterministic vehicle compiler.
 *
 * The implementation is a thin wrapper over SHA-256 + the canonical
 * form of the inputs. The canonical form is:
 *
 * ```
 * compilation:v1
 * |catalog={catalogRevision}
 * |compiler={compilerVersion}
 * |{definition.canonicalForm()}
 * ```
 *
 * Same inputs -> same canonical string -> same SHA-256 -> same
 * `contentHash`. The implementation is **total** for any
 * `VehicleDefinition` that passes its own `validate()`; an
 * ill-formed definition produces a typed `CompilationNonDeterministic`
 * error (per `.ai/STANDARDS.md` 7).
 *
 * Phase 2 replaces this with the full 18-step pipeline (per
 * `.ai/skills/04-vehicle-dsl-compiler/SKILL.md` section 8) without
 * changing the public interface. The `DeterministicVehicleCompiler`
 * becomes the front-end that delegates to the parser + resolver
 * + type-checker pipeline.
 */
class DeterministicVehicleCompiler : VehicleCompiler {

    override fun compile(
        definition: VehicleDefinition,
        catalogRevision: CatalogRevision,
        compilerVersion: CompilerVersion,
    ): Result<Compilation> {
        // Step 1: validate the definition.
        val validation = definition.validate()
        if (validation.isFailure) {
            return Result.failure(
                validation.exceptionOrNull() as? FoundryError
                    ?: FoundryError.CompilationNonDeterministic(
                        reason = "definition validation failed: ${validation.exceptionOrNull()?.message}",
                    ),
            )
        }

        // Step 2: build the canonical form. Same inputs -> same string.
        val canonical = buildString {
            append("compilation:v1")
            append("|catalog=").append(catalogRevision.value)
            append("|compiler=").append(compilerVersion.value)
            append('|')
            append(definition.canonicalForm())
        }

        // Step 3: hash the canonical form. SHA-256 -> ContentHash.
        val contentHash = ContentHash.of(canonical)

        // Step 4: assemble the result. Phase 1 has no warnings.
        return Result.success(
            Compilation(
                contentHash = contentHash,
                warnings = emptyList(),
            ),
        )
    }
}
