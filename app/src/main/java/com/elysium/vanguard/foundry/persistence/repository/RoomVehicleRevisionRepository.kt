package com.elysium.vanguard.foundry.persistence.repository

import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.ontology.ids.VehicleRevisionId
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.revision.VehicleRevision
import com.elysium.vanguard.foundry.persistence.daos.VehicleRevisionDao
import com.elysium.vanguard.foundry.persistence.entities.VehicleRevisionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The Room-backed `VehicleRevisionRepository`. The
 * revision is **immutable** (per ADR-0006) — the
 * repository has only `append` + `getById` +
 * `getByProject` + `getByContentHash` + `count` (no
 * `update` + no `delete`).
 *
 * The `append` is a write that uses
 * `OnConflictStrategy.ABORT` — a duplicate `id`
 * is rejected with a typed error. The
 * `FoundryError.FrozenRevisionMutationRejected`
 * is the platform's hard guard at the domain
 * level; the repository enforces the
 * persistence-level guard (no `update` + no
 * `delete`).
 */
@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
class RoomVehicleRevisionRepository(
    private val dao: VehicleRevisionDao,
) : VehicleRevisionRepository {

    override suspend fun append(revision: VehicleRevision): Result<Unit> = try {
        dao.insert(VehicleRevisionEntity.fromDomain(revision))
        Result.success(Unit)
    } catch (e: android.database.sqlite.SQLiteConstraintException) {
        Result.failure(
            FoundryError.VehicleDefinitionInvalid(
                field = "VehicleRevision.id",
                reason = "revision ${revision.id.value} already exists",
            ),
        )
    }

    override suspend fun getById(id: VehicleRevisionId): VehicleRevision? =
        dao.getById(id.value.toString())?.toDomain()

    override suspend fun count(): Int = dao.count()

    override suspend fun getByProject(projectId: ProjectId): List<VehicleRevision> =
        dao.getByProject(projectId.value.toString()).map(VehicleRevisionEntity::toDomain)

    override suspend fun getByContentHash(hash: String): VehicleRevision? =
        dao.getByContentHash(hash)?.toDomain()

    override fun observeAll(): Flow<List<VehicleRevision>> =
        dao.observeAll().map { list -> list.map(VehicleRevisionEntity::toDomain) }
}
