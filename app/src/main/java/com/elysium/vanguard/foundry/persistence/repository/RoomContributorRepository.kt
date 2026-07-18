package com.elysium.vanguard.foundry.persistence.repository

import com.elysium.vanguard.foundry.core.concurrency.OptimisticConcurrency
import com.elysium.vanguard.foundry.core.contributor.Contributor
import com.elysium.vanguard.foundry.core.ontology.ids.ContributorId
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.persistence.daos.ContributorDao
import com.elysium.vanguard.foundry.persistence.entities.ContributorEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The Room-backed `ContributorRepository`. The
 * contributor's PII (the `email` field) is stored
 * as-is in Phase F1; the encryption at rest is wired
 * in Phase 5 (per skill 12 + the platform's Zero
 * Trust model).
 */
@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
class RoomContributorRepository(
    private val dao: ContributorDao,
) : ContributorRepository {

    override suspend fun insert(contributor: Contributor): Result<Unit> = try {
        dao.insert(ContributorEntity.fromDomain(contributor))
        Result.success(Unit)
    } catch (e: android.database.sqlite.SQLiteConstraintException) {
        Result.failure(
            FoundryError.VehicleDefinitionInvalid(
                field = "Contributor.id",
                reason = "contributor ${contributor.id.value} already exists",
            ),
        )
    }

    override suspend fun update(contributor: Contributor, expectedVersion: Long): Result<Contributor> {
        val current = dao.getById(contributor.id.value.toString())
            ?: return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "Contributor.id",
                    reason = "contributor ${contributor.id.value} does not exist",
                ),
            )
        val conflict = OptimisticConcurrency.check(
            aggregateType = "Contributor",
            aggregateId = contributor.id.value.toString(),
            expectedVersion = expectedVersion,
            actualVersion = current.version,
        )
        if (conflict != null) {
            return Result.failure(conflict)
        }
        val rows = dao.update(ContributorEntity.fromDomain(contributor))
        if (rows == 0) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "Contributor.id",
                    reason = "contributor ${contributor.id.value} was deleted concurrently",
                ),
            )
        }
        return Result.success(contributor)
    }

    override suspend fun deleteById(id: ContributorId): Result<Unit> {
        val rows = dao.deleteById(id.value.toString())
        if (rows == 0) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "Contributor.id",
                    reason = "contributor ${id.value} does not exist",
                ),
            )
        }
        return Result.success(Unit)
    }

    override suspend fun getById(id: ContributorId): Contributor? =
        dao.getById(id.value.toString())?.toDomain()

    override fun observeAll(): Flow<List<Contributor>> =
        dao.observeAll().map { list -> list.map(ContributorEntity::toDomain) }

    override suspend fun count(): Int = dao.count()

    override suspend fun getByEmail(email: String): Contributor? =
        dao.getByEmail(email)?.toDomain()
}
