package com.elysium.vanguard.foundry.persistence.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.elysium.vanguard.foundry.persistence.entities.VehicleProgramEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for the `VehicleProgramEntity`.
 * The `revisions` list is stored as a
 * unit-separator-joined string (per the
 * `Converters.stringListToString` pattern).
 */
@Dao
interface VehicleProgramDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(program: VehicleProgramEntity)

    @Update
    suspend fun update(program: VehicleProgramEntity): Int

    @Query("DELETE FROM vehicle_programs WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT * FROM vehicle_programs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): VehicleProgramEntity?

    @Query("SELECT * FROM vehicle_programs WHERE project_id = :projectId ORDER BY name ASC")
    suspend fun getByProject(projectId: String): List<VehicleProgramEntity>

    @Query("SELECT * FROM vehicle_programs ORDER BY name ASC")
    fun observeAll(): Flow<List<VehicleProgramEntity>>

    @Query("SELECT COUNT(*) FROM vehicle_programs")
    suspend fun count(): Int
}
