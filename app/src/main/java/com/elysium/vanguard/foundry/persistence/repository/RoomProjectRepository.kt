package com.elysium.vanguard.foundry.persistence.repository

import com.elysium.vanguard.foundry.core.concurrency.OptimisticConcurrency
import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.ontology.ids.UserId
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.project.Project
import com.elysium.vanguard.foundry.persistence.daos.ProjectDao
import com.elysium.vanguard.foundry.persistence.entities.ProjectEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The Room-backed `ProjectRepository`. The
 * repository is the only path to a persistent
 * `Project` mutation.
 *
 * Per `.ai/AGENTS.md` 24.1 + the Foundry's
 * "one owner, many readers" rule (skill 03):
 *   - `insert` writes a new `Project` (rejects
 *     duplicates with a typed error).
 *   - `update` is **optimistic-concurrency-controlled**:
 *     the caller's `expectedVersion` must match the
 *     stored version; otherwise the call returns a
 *     `FoundryError.RevisionConflict`.
 *   - `deleteById` removes the row; the operation
 *     is idempotent.
 *   - `getById` is a single-shot read; `observeAll`
 *     is a `Flow` for reactive UI.
 */
@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
class RoomProjectRepository(
    private val dao: ProjectDao,
) : ProjectRepository {

    override suspend fun insert(project: Project): Result<Unit> = try {
        dao.insert(ProjectEntity.fromDomain(project))
        Result.success(Unit)
    } catch (e: android.database.sqlite.SQLiteConstraintException) {
        Result.failure(
            FoundryError.VehicleDefinitionInvalid(
                field = "Project.id",
                reason = "project ${project.id.value} already exists",
            ),
        )
    }

    override suspend fun update(project: Project, expectedVersion: Long): Result<Project> {
        // Read the stored version. The read is racy with
        // concurrent updates; Room serializes the update at
        // the SQLite level. The race here is "two updates with
        // the same expectedVersion"; the second is rejected by
        // the SQLite write + the post-write version check.
        val current = dao.getById(project.id.value.toString())
            ?: return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "Project.id",
                    reason = "project ${project.id.value} does not exist",
                ),
            )
        val conflict = OptimisticConcurrency.check(
            aggregateType = "Project",
            aggregateId = project.id.value.toString(),
            expectedVersion = expectedVersion,
            actualVersion = current.version,
        )
        if (conflict != null) {
            return Result.failure(conflict)
        }
        val newEntity = ProjectEntity.fromDomain(project)
        val rows = dao.update(newEntity)
        if (rows == 0) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "Project.id",
                    reason = "project ${project.id.value} was deleted concurrently",
                ),
            )
        }
        return Result.success(project)
    }

    override suspend fun deleteById(id: ProjectId): Result<Unit> {
        val rows = dao.deleteById(id.value.toString())
        if (rows == 0) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "Project.id",
                    reason = "project ${id.value} does not exist",
                ),
            )
        }
        return Result.success(Unit)
    }

    override suspend fun getById(id: ProjectId): Project? =
        dao.getById(id.value.toString())?.toDomain()

    override fun observeAll(): Flow<List<Project>> =
        dao.observeAll().map { list -> list.map(ProjectEntity::toDomain) }

    override suspend fun count(): Int = dao.count()

    override suspend fun getByOwner(ownerId: UserId): List<Project> =
        dao.getByOwner(ownerId.value.toString()).map(ProjectEntity::toDomain)
}
