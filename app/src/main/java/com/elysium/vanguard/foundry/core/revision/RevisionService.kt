package com.elysium.vanguard.foundry.core.revision

import com.elysium.vanguard.foundry.core.compiler.Compilation
import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.ontology.ids.VehicleRevisionId
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.ontology.primitives.RepresentationLevel
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp
import com.elysium.vanguard.foundry.core.provenance.ProvenanceService
import com.elysium.vanguard.foundry.core.revision.VehicleDefinition
import com.elysium.vanguard.foundry.core.scene.SceneManifestGenerator

/**
 * Use case: freeze a `Compilation` into a `VehicleRevision` +
 * the hard guard against mutating a frozen revision.
 *
 * The `freeze` operation is the only legitimate way to create a
 * `VehicleRevision`. The flow is:
 *   1. Generate the `SceneManifest` from the `Compilation` +
 *      the originating `VehicleDefinition` (the `definition`
 *      parameter is passed alongside the `compilation` because
 *      the `Compilation.contentHash` alone does not carry the
 *      component list — the manifest is a derived artifact).
 *   2. Create a `ProvenanceRecord` for the `contentHash`,
 *      signed with the revision's signing key.
 *   3. Assemble the `VehicleRevision` with `isImmutable = true`.
 *
 * The `modifyFrozenRevision` is the platform's hard guard. It
 * **always** throws `FoundryError.FrozenRevisionMutationRejected`.
 * The integration test asserts this with `assertFailsWith`.
 */
class RevisionService(
    private val sceneManifestGenerator: SceneManifestGenerator = SceneManifestGenerator(),
    private val provenanceService: ProvenanceService = ProvenanceService(),
    private val clock: Timestamp.Companion.TimestampSource = Timestamp.monotonicWallClock(),
    private val signingKey: ByteArray = DEFAULT_SIGNING_KEY,
) {

    /**
     * Freeze a `Compilation` into a `VehicleRevision`. The
     * `definition` is required because the `SceneManifest`
     * derives its component list from the definition's
     * parameters.
     */
    fun freeze(
        projectId: ProjectId,
        compilation: Compilation,
        definition: VehicleDefinition,
    ): Result<VehicleRevision> {
        // 1. Generate the scene manifest. The manifest's
        //    representation level defaults to PARAMETRIC_FUNCTIONAL
        //    because no validated OEM assets exist in Phase 1.
        val sceneManifest = sceneManifestGenerator.generate(
            compilation = compilation,
            definition = definition,
            representationLevel = RepresentationLevel.PARAMETRIC_FUNCTIONAL,
        )

        // 2. Create the provenance record. The subject is the
        //    content hash; the source is the compiler version
        //    captured in the canonical form. The signing key
        //    binds the provenance to the revision.
        val provenance = provenanceService.createProvenance(
            subjectId = compilation.contentHash.value,
            source = "compiler:deterministic-v1",
            signingKey = signingKey,
        )

        // 3. Assemble the revision. `isImmutable = true` is
        //    enforced by the data class `init` block.
        return Result.success(
            VehicleRevision(
                id = VehicleRevisionId.random(),
                projectId = projectId,
                contentHash = compilation.contentHash,
                provenance = provenance,
                sceneManifest = sceneManifest,
                representationLevel = RepresentationLevel.PARAMETRIC_FUNCTIONAL,
                createdAt = clock.now(),
                isImmutable = true,
            ),
        )
    }

    /**
     * The hard guard. A `VehicleRevision`, once frozen, cannot
     * mutate. This method **always** throws
     * `FoundryError.FrozenRevisionMutationRejected` (per
     * `.ai/STANDARDS.md` 7 + ADR-0006). A mutation attempt is a
     * P0 contract violation.
     */
    @Suppress("UNUSED_PARAMETER")
    fun modifyFrozenRevision(id: VehicleRevisionId): Nothing {
        throw FoundryError.FrozenRevisionMutationRejected(revisionId = id)
    }

    companion object {
        /**
         * Phase 1 default signing key. In production the key is
         * the user's Ed25519 / ML-DSA-65 keypair from the
         * secure enclave. Phase 7 (per skill 12).
         */
        val DEFAULT_SIGNING_KEY: ByteArray = "foundry-phase-1-signing-key".toByteArray()
    }
}
