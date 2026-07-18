package com.elysium.vanguard.foundry.core.artifact

import com.elysium.vanguard.foundry.core.concurrency.OptimisticConcurrency
import com.elysium.vanguard.foundry.core.ontology.ids.EngineeringArtifactId
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp

/**
 * Use case: create + mutate an `EngineeringArtifact`.
 *
 * Phase 1 ships the reference shape + the optimistic-concurrency
 * control. The actual byte storage (skill 08's content-addressed
 * store) is wired in Phase 2; for Phase 1 the service holds the
 * reference in memory.
 */
class EngineeringArtifactService(
    private val clock: Timestamp.Companion.TimestampSource = Timestamp.monotonicWallClock(),
) {

    fun createArtifact(
        contentHash: ContentHash,
        format: EngineeringArtifactFormat,
        sizeBytes: Long,
        subjectId: String,
    ): Result<EngineeringArtifact> {
        if (sizeBytes < 0) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "EngineeringArtifact.sizeBytes",
                    reason = "sizeBytes must be non-negative, got $sizeBytes",
                ),
            )
        }
        if (subjectId.isBlank()) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "EngineeringArtifact.subjectId",
                    reason = "subjectId must not be blank",
                ),
            )
        }
        return Result.success(
            EngineeringArtifact(
                id = EngineeringArtifactId.random(),
                contentHash = contentHash,
                format = format,
                sizeBytes = sizeBytes,
                subjectId = subjectId.trim(),
                createdAt = clock.now(),
                version = 0L,
            ),
        )
    }

    /**
     * Update the artifact's `subjectId` (the thing the artifact
     * describes). The `contentHash` + `format` + `sizeBytes` are
     * immutable — a change to the bytes is a new artifact, not
     * an update to the existing one.
     */
    fun reassignSubject(
        artifact: EngineeringArtifact,
        newSubjectId: String,
        expectedVersion: Long,
    ): Result<EngineeringArtifact> {
        val conflict = OptimisticConcurrency.check(
            aggregateType = "EngineeringArtifact",
            aggregateId = artifact.id.value.toString(),
            expectedVersion = expectedVersion,
            actualVersion = artifact.version,
        )
        if (conflict != null) {
            return Result.failure(conflict)
        }
        if (newSubjectId.isBlank()) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "EngineeringArtifact.subjectId",
                    reason = "subjectId must not be blank",
                ),
            )
        }
        if (artifact.subjectId == newSubjectId) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "EngineeringArtifact.subjectId",
                    reason = "new subjectId must differ from current subjectId",
                ),
            )
        }
        return Result.success(
            artifact.copy(
                subjectId = newSubjectId.trim(),
                version = artifact.version + 1,
            ),
        )
    }
}
