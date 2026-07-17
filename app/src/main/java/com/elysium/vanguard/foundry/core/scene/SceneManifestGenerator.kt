package com.elysium.vanguard.foundry.core.scene

import com.elysium.vanguard.foundry.core.compiler.Compilation
import com.elysium.vanguard.foundry.core.ontology.primitives.RepresentationLevel
import com.elysium.vanguard.foundry.core.revision.VehicleDefinition

/**
 * Phase 1 implementation of the scene-manifest generator.
 *
 * The generator derives a `SceneManifest` from the `Compilation` +
 * the `VehicleDefinition`. The component list is a Phase 1 stub:
 * each parameter of the definition becomes a "component" with a
 * derived label. The LOD list is a fixed set of 3 placeholders
 * (LOD0 / LOD1 / LOD2). The representation level defaults to
 * `PARAMETRIC_FUNCTIONAL` because no validated OEM assets exist
 * in Phase 1.
 *
 * Phase 3 replaces this with the full 3D pipeline + the
 * `Canonical3DAsset` references + the validated `LOD`
 * distribution. The public interface is stable.
 */
class SceneManifestGenerator {

    fun generate(
        compilation: Compilation,
        definition: VehicleDefinition,
        representationLevel: RepresentationLevel = RepresentationLevel.PARAMETRIC_FUNCTIONAL,
    ): SceneManifest {
        val components = definition.parameters.entries
            .sortedBy { it.key }
            .map { (key, value) ->
                ComponentRef(
                    id = "$key=$value",
                    label = humanize(key),
                )
            }
        val lods = listOf(
            LodRef(level = 0, resolution = "high"),
            LodRef(level = 1, resolution = "medium"),
            LodRef(level = 2, resolution = "low"),
        )
        return SceneManifest(
            revisionContentHash = compilation.contentHash,
            components = components,
            lods = lods,
            representationLevel = representationLevel,
        )
    }

    /**
     * Convert a parameter key (e.g. `powertrain.battery.kwh`) to a
     * human-readable label (e.g. `Powertrain / Battery / Kwh`).
     */
    private fun humanize(key: String): String =
        key.split(".")
            .joinToString(separator = " / ") { part ->
                part.replaceFirstChar { it.uppercase() }
            }
}
