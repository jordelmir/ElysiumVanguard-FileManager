package com.elysium.vanguard.foundry.core.revision

import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.ontology.ids.VehicleRevisionId
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.RepresentationLevel
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp
import com.elysium.vanguard.foundry.core.provenance.ProvenanceRecord
import com.elysium.vanguard.foundry.core.scene.SceneManifest

/**
 * The unit of immutability. A `VehicleRevision` is the
 * **frozen, signed, content-addressed** record of one specific
 * vehicle configuration. The integration test asserts:
 *   - `isImmutable == true`
 *   - `provenance.isComplete == true`
 *   - `sceneManifest != null`
 *   - `representationLevel` is set
 *
 * Per `docs/foundry/domain-ownership.md` section 2.3 + ADR-0006:
 *   - A `VehicleRevision`, once created, cannot mutate. Any
 *     change is a new `VehicleRevision` that points to its
 *     predecessor (the chain is content-addressed + signed).
 *   - The `RevisionService.modifyFrozenRevision` is the hard
 *     guard: it always throws `FrozenRevisionMutationRejected`
 *     (per `.ai/AGENTS.md` 24.1 + `.ai/STANDARDS.md` 7).
 */
data class VehicleRevision(
    val id: VehicleRevisionId,
    val projectId: ProjectId,
    val contentHash: ContentHash,
    val provenance: ProvenanceRecord,
    val sceneManifest: SceneManifest,
    val representationLevel: RepresentationLevel,
    val createdAt: Timestamp,
    val isImmutable: Boolean = true,
) {
    init {
        // Belt-and-suspenders: the data class itself rejects a
        // construction that doesn't mark the revision immutable.
        // The `RevisionService.freeze` is the only legitimate
        // constructor entry point, and it always sets this true.
        require(isImmutable) {
            "VehicleRevision.isImmutable must be true; a revision that is not immutable is not a revision"
        }
    }
}
