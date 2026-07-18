package com.elysium.vanguard.foundry.persistence.repository

import com.elysium.vanguard.foundry.core.artifact.EngineeringArtifact
import com.elysium.vanguard.foundry.core.concurrency.OptimisticConcurrency
import com.elysium.vanguard.foundry.core.ontology.ids.EngineeringArtifactId
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.persistence.daos.EngineeringArtifactDao
import com.elysium.vanguard.foundry.persistence.entities.EngineeringArtifactEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The Room-backed `EngineeringArtifactRepository`.
 * The artifact's `contentHash` + `format` +
 * `sizeBytes` are immutable (a change to the
 * bytes is a new artifact).
 */
@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
class RoomEngineeringArtifactRepository(
    private val dao: EngineeringArtifactDao,
) : EngineeringArtifactRepository {

    override suspend fun insert(artifact: EngineeringArtifact): Result<Unit> = try {
        dao.insert(EngineeringArtifactEntity.fromDomain(artifact))
        Result.success(Unit)
    } catch (e: android.database.sqlite.SQLiteConstraintException) {
        Result.failure(
            FoundryError.VehicleDefinitionInvalid(
                field = "EngineeringArtifact.id",
                reason = "artifact ${artifact.id.value} already exists",
            ),
        )
    }

    override suspend fun update(artifact: EngineeringArtifact, expectedVersion: Long): Result<EngineeringArtifact> {
        val current = dao.getById(artifact.id.value.toString())
            ?: return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "EngineeringArtifact.id",
                    reason = "artifact ${artifact.id.value} does not exist",
                ),
            )
        val conflict = OptimisticConcurrency.check(
            aggregateType = "EngineeringArtifact",
            aggregateId = artifact.id.value.toString(),
            expectedVersion = expectedVersion,
            actualVersion = current.version,
        )
        if (conflict != null) {
            return Result.failure(conflict)
        }
        val rows = dao.update(EngineeringArtifactEntity.fromDomain(artifact))
        if (rows == 0) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "EngineeringArtifact.id",
                    reason = "artifact ${artifact.id.value} was deleted concurrently",
                ),
            )
        }
        return Result.success(artifact)
    }

    override suspend fun deleteById(id: EngineeringArtifactId): Result<Unit> {
        val rows = dao.deleteById(id.value.toString())
        if (rows == 0) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "EngineeringArtifact.id",
                    reason = "artifact ${id.value} does not exist",
                ),
            )
        }
        return Result.success(Unit)
    }

    override suspend fun getById(id: EngineeringArtifactId): EngineeringArtifact? =
        dao.getById(id.value.toString())?.toDomain()

    override fun observeAll(): Flow<List<EngineeringArtifact>> =
        dao.observeAll().map { list -> list.map(EngineeringArtifactEntity::toDomain) }

    override suspend fun count(): Int = dao.count()

    override suspend fun getByContentHash(hash: String): EngineeringArtifact? =
        dao.getByContentHash(hash)?.toDomain()

    override suspend fun getBySubject(subjectId: String): List<EngineeringArtifact> =
        dao.getBySubject(subjectId).map(EngineeringArtifactEntity::toDomain)
}
