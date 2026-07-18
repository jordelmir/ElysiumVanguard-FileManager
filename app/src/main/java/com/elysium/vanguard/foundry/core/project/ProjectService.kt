package com.elysium.vanguard.foundry.core.project

import com.elysium.vanguard.foundry.core.concurrency.OptimisticConcurrency
import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.ontology.ids.UserId
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp

/**
 * Use case: create + mutate a `Project`.
 *
 * Mutations are **optimistic-concurrency-controlled**: the caller
 * supplies the expected `version`; the service raises a typed
 * `FoundryError.RevisionConflict` if the stored version differs.
 *
 * The signature returns `Result<Project, FoundryError>` (per
 * `.ai/AGENTS.md` 24.1) so the consumer can pattern-match on
 * the typed error.
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
                version = 0L,
            ),
        )
    }

    /**
     * Rename a project. Optimistic-concurrency-controlled.
     */
    fun rename(
        project: Project,
        newName: String,
        expectedVersion: Long,
    ): Result<Project> {
        val conflict = OptimisticConcurrency.check(
            aggregateType = "Project",
            aggregateId = project.id.value.toString(),
            expectedVersion = expectedVersion,
            actualVersion = project.version,
        )
        if (conflict != null) {
            return Result.failure(conflict)
        }
        if (newName.isBlank()) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "Project.name",
                    reason = "name must not be blank",
                ),
            )
        }
        if (newName.length > Project.MAX_NAME_LENGTH) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "Project.name",
                    reason = "name length ${newName.length} exceeds max ${Project.MAX_NAME_LENGTH}",
                ),
            )
        }
        if (project.name == newName) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "Project.name",
                    reason = "new name must differ from current name",
                ),
            )
        }
        return Result.success(
            project.copy(
                name = newName.trim(),
                version = project.version + 1,
            ),
        )
    }

    /**
     * Archive a project. Optimistic-concurrency-controlled.
     * Archiving is one-way (per `.ai/STANDARDS.md` 2.2 + ADR-0006):
     * a project that is `ARCHIVED` cannot transition to `DRAFT` or
     * `ACTIVE`.
     */
    fun archive(
        project: Project,
        expectedVersion: Long,
    ): Result<Project> {
        val conflict = OptimisticConcurrency.check(
            aggregateType = "Project",
            aggregateId = project.id.value.toString(),
            expectedVersion = expectedVersion,
            actualVersion = project.version,
        )
        if (conflict != null) {
            return Result.failure(conflict)
        }
        if (project.status == ProjectStatus.ARCHIVED) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "Project.status",
                    reason = "project is already archived",
                ),
            )
        }
        return Result.success(
            project.copy(
                status = ProjectStatus.ARCHIVED,
                version = project.version + 1,
            ),
        )
    }
}
