package com.elysium.vanguard.foundry.core.program

import com.elysium.vanguard.foundry.core.concurrency.OptimisticConcurrency
import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.ontology.ids.VehicleProgramId
import com.elysium.vanguard.foundry.core.ontology.ids.VehicleRevisionId
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp

/**
 * Use case: create + mutate a `VehicleProgram`.
 *
 * Mutations are **optimistic-concurrency-controlled**: the caller
 * supplies the expected `version`; the service raises a typed
 * `FoundryError.RevisionConflict` if the stored version differs.
 *
 * The `revisions` list is **append-only**: a revision is added; a
 * revision is never removed. The order is preserved.
 */
class VehicleProgramService(
    private val clock: Timestamp.Companion.TimestampSource = Timestamp.monotonicWallClock(),
) {

    fun createProgram(
        projectId: ProjectId,
        name: String,
        description: String = "",
    ): Result<VehicleProgram> {
        if (name.isBlank()) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "VehicleProgram.name",
                    reason = "name must not be blank",
                ),
            )
        }
        if (description.length > VehicleProgram.MAX_DESCRIPTION_LENGTH) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "VehicleProgram.description",
                    reason = "description length ${description.length} exceeds max ${VehicleProgram.MAX_DESCRIPTION_LENGTH}",
                ),
            )
        }
        return Result.success(
            VehicleProgram(
                id = VehicleProgramId.random(),
                projectId = projectId,
                name = name.trim(),
                description = description.trim(),
                revisions = emptyList(),
                status = VehicleProgramStatus.DRAFT,
                createdAt = clock.now(),
                version = 0L,
            ),
        )
    }

    /**
     * Append a revision to the program. The revision list is
     * append-only; the operation is optimistic-concurrency-controlled.
     */
    fun addRevision(
        program: VehicleProgram,
        revisionId: VehicleRevisionId,
        expectedVersion: Long,
    ): Result<VehicleProgram> {
        val conflict = OptimisticConcurrency.check(
            aggregateType = "VehicleProgram",
            aggregateId = program.id.value.toString(),
            expectedVersion = expectedVersion,
            actualVersion = program.version,
        )
        if (conflict != null) {
            return Result.failure(conflict)
        }
        if (program.revisions.contains(revisionId)) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "VehicleProgram.revisions",
                    reason = "revision ${revisionId.value} is already in the program",
                ),
            )
        }
        return Result.success(
            program.copy(
                revisions = program.revisions + revisionId,
                version = program.version + 1,
            ),
        )
    }

    /**
     * Transition the program status. The transition is
     * append-only in the audit trail; this method only updates
     * the in-memory snapshot.
     */
    fun transitionStatus(
        program: VehicleProgram,
        newStatus: VehicleProgramStatus,
        expectedVersion: Long,
    ): Result<VehicleProgram> {
        val conflict = OptimisticConcurrency.check(
            aggregateType = "VehicleProgram",
            aggregateId = program.id.value.toString(),
            expectedVersion = expectedVersion,
            actualVersion = program.version,
        )
        if (conflict != null) {
            return Result.failure(conflict)
        }
        if (program.status == VehicleProgramStatus.ARCHIVED && newStatus != VehicleProgramStatus.ARCHIVED) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "VehicleProgram.status",
                    reason = "cannot transition from ARCHIVED to ${newStatus.name}",
                ),
            )
        }
        return Result.success(
            program.copy(
                status = newStatus,
                version = program.version + 1,
            ),
        )
    }
}
