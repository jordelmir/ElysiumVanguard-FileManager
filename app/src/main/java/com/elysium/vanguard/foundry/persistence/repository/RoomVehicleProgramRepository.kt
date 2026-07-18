package com.elysium.vanguard.foundry.persistence.repository

import com.elysium.vanguard.foundry.core.concurrency.OptimisticConcurrency
import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.ontology.ids.VehicleProgramId
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.program.VehicleProgram
import com.elysium.vanguard.foundry.persistence.daos.VehicleProgramDao
import com.elysium.vanguard.foundry.persistence.entities.VehicleProgramEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The Room-backed `VehicleProgramRepository`. The
 * `revisions` list is stored as a unit-separator-joined
 * string (per `Converters.stringListToString`).
 */
@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
class RoomVehicleProgramRepository(
    private val dao: VehicleProgramDao,
) : VehicleProgramRepository {

    override suspend fun insert(program: VehicleProgram): Result<Unit> = try {
        dao.insert(VehicleProgramEntity.fromDomain(program))
        Result.success(Unit)
    } catch (e: android.database.sqlite.SQLiteConstraintException) {
        Result.failure(
            FoundryError.VehicleDefinitionInvalid(
                field = "VehicleProgram.id",
                reason = "program ${program.id.value} already exists",
            ),
        )
    }

    override suspend fun update(program: VehicleProgram, expectedVersion: Long): Result<VehicleProgram> {
        val current = dao.getById(program.id.value.toString())
            ?: return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "VehicleProgram.id",
                    reason = "program ${program.id.value} does not exist",
                ),
            )
        val conflict = OptimisticConcurrency.check(
            aggregateType = "VehicleProgram",
            aggregateId = program.id.value.toString(),
            expectedVersion = expectedVersion,
            actualVersion = current.version,
        )
        if (conflict != null) {
            return Result.failure(conflict)
        }
        val rows = dao.update(VehicleProgramEntity.fromDomain(program))
        if (rows == 0) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "VehicleProgram.id",
                    reason = "program ${program.id.value} was deleted concurrently",
                ),
            )
        }
        return Result.success(program)
    }

    override suspend fun deleteById(id: VehicleProgramId): Result<Unit> {
        val rows = dao.deleteById(id.value.toString())
        if (rows == 0) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "VehicleProgram.id",
                    reason = "program ${id.value} does not exist",
                ),
            )
        }
        return Result.success(Unit)
    }

    override suspend fun getById(id: VehicleProgramId): VehicleProgram? =
        dao.getById(id.value.toString())?.toDomain()

    override fun observeAll(): Flow<List<VehicleProgram>> =
        dao.observeAll().map { list -> list.map(VehicleProgramEntity::toDomain) }

    override suspend fun count(): Int = dao.count()

    override suspend fun getByProject(projectId: ProjectId): List<VehicleProgram> =
        dao.getByProject(projectId.value.toString()).map(VehicleProgramEntity::toDomain)
}
