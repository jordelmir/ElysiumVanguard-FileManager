package com.elysium.vanguard.foundry.persistence.repository

import com.elysium.vanguard.foundry.core.ontology.ids.ProvenanceRecordId
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.provenance.ProvenanceRecord
import com.elysium.vanguard.foundry.persistence.daos.ProvenanceRecordDao
import com.elysium.vanguard.foundry.persistence.entities.ProvenanceRecordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The Room-backed `ProvenanceRecordRepository`.
 * The record is **append-only** (per ADR-0006) —
 * the repository has only `append` + `getById` +
 * `getBySubject` + `count` (no `update` + no
 * `delete`).
 *
 * The `append` is a write that uses
 * `OnConflictStrategy.ABORT` — a duplicate `id`
 * is rejected with a typed error.
 */
@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
class RoomProvenanceRecordRepository(
    private val dao: ProvenanceRecordDao,
) : ProvenanceRecordRepository {

    override suspend fun append(record: ProvenanceRecord): Result<Unit> = try {
        dao.insert(ProvenanceRecordEntity.fromDomain(record))
        Result.success(Unit)
    } catch (e: android.database.sqlite.SQLiteConstraintException) {
        Result.failure(
            FoundryError.VehicleDefinitionInvalid(
                field = "ProvenanceRecord.id",
                reason = "provenance record ${record.id.value} already exists",
            ),
        )
    }

    override suspend fun getById(id: ProvenanceRecordId): ProvenanceRecord? =
        dao.getById(id.value.toString())?.toDomain()

    override fun observeAll(): Flow<List<ProvenanceRecord>> =
        dao.observeAll().map { list -> list.map(ProvenanceRecordEntity::toDomain) }

    override suspend fun count(): Int = dao.count()

    override suspend fun getBySubject(subjectId: String): List<ProvenanceRecord> =
        dao.getBySubject(subjectId).map(ProvenanceRecordEntity::toDomain)
}
