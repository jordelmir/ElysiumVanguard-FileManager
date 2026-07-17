package com.elysium.vanguard.foundry.core.scene

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.RepresentationLevel

/**
 * A typed reference to a 3D scene for a `VehicleRevision`.
 *
 * Per `docs/foundry/domain-ownership.md` section 4.3 +
 * `.ai/skills/06-3d-cad-asset-pipeline/SKILL.md` section 7:
 *   - The manifest is a list of component references + their
 *     `LOD` selection + their `CoordinateSystem` + their
 *     parent-child relationship.
 *   - The manifest is signed; the manifest's content hash is
 *     the canonical id.
 *   - The manifest carries a `representationLevel` declaration
 *     (per `.ai/STANDARDS.md` 2.1).
 *
 * Phase 1 ships a lightweight manifest: the component list is
 * derived from the `VehicleDefinition.parameters`; the LODs are
 * stub placeholders. Phase 3 replaces the stub with the real
 * 3D pipeline + the validated `Canonical3DAsset` references.
 */
data class SceneManifest(
    val revisionContentHash: ContentHash,
    val components: List<ComponentRef>,
    val lods: List<LodRef>,
    val representationLevel: RepresentationLevel,
) {
    /**
     * The manifest's own content hash. Computed from the
     * canonical form of the manifest. Used to verify the
     * manifest has not been tampered with at load time.
     */
    val contentHash: ContentHash
        get() {
            val canonical = buildString {
                append("scene-manifest:v1")
                append("|revision=").append(revisionContentHash.value)
                append("|level=").append(representationLevel.name)
                append("|components=")
                append(components.sortedBy { it.id }.joinToString(";") { "${it.id}:${it.label}" })
                append("|lods=")
                append(lods.sortedBy { it.level }.joinToString(";") { "${it.level}:${it.resolution}" })
            }
            return ContentHash.of(canonical)
        }
}

/**
 * A typed reference to a component in the 3D scene. Phase 1 uses
 * simple string IDs; Phase 3 replaces with `Canonical3DAsset`
 * references.
 */
data class ComponentRef(
    val id: String,
    val label: String,
)

/**
 * A typed reference to a level-of-detail. Phase 1 uses simple
 * resolution strings; Phase 3 replaces with the full LOD
 * distribution (LOD0..LODn + collision proxy).
 */
data class LodRef(
    val level: Int,
    val resolution: String,
)
