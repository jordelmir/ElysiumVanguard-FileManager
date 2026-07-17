package com.elysium.vanguard.foundry.core.project

import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.ontology.ids.UserId
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp

/**
 * Use case: create a new `Project`.
 *
 * The use case is a thin wrapper around the invariants + the ID
 * generation + the timestamp. The persistence (Room) + the audit
 * trail (skill 09) are added in later increments of Phase 1; for
 * Phase 1 the use case is a pure function that returns a new
 * `Project` instance.
 *
 * The signature returns `Result<Project, FoundryError>` (per
 * `.ai/AGENTS.md` 24.1) so the consumer can pattern-match on
 * the typed error. The current error variant is
 * `VehicleDefinitionInvalid` for a blank / oversized name; a
 * richer error taxonomy is added when the persistence +
 * audit-trail layers are wired in.
 */
class ProjectService(
    private val clock: Timestamp.Companion.TimestampSource = Timestamp.monotonicWallClock(),
) {

    fun createProject(
        ownerId: UserId,
        name: String,
    ): Result<Project> {
        if (name.isBlank()) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "Project.name",
                    reason = "name must not be blank",
                ),
            )
        }
        if (name.length > Project.MAX_NAME_LENGTH) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "Project.name",
                    reason = "name length ${name.length} exceeds max ${Project.MAX_NAME_LENGTH}",
                ),
            )
        }
        return Result.success(
            Project(
                id = ProjectId.random(),
                name = name.trim(),
                ownerId = ownerId,
                status = ProjectStatus.DRAFT,
                createdAt = clock.now(),
            ),
        )
    }
}
