package com.elysium.vanguard.foundry.persistence.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.elysium.vanguard.foundry.persistence.entities.VehicleRevisionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for the `VehicleRevisionEntity`.
 * The revision is **immutable** (per ADR-0006)
 * — the DAO only has `insert` (no `update`).
 * The "update" path is "freeze a new revision",
 * not "modify an existing one".
 */
@Dao
interface VehicleRevisionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(revision: VehicleRevisionEntity)

    @Query("DELETE FROM vehicle_revisions WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT * FROM vehicle_revisions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): VehicleRevisionEntity?

    @Query("SELECT * FROM vehicle_revisions WHERE project_id = :projectId ORDER BY created_at_epoch_ms DESC")
    suspend fun getByProject(projectId: String): List<VehicleRevisionEntity>

    @Query("SELECT * FROM vehicle_revisions WHERE content_hash = :hash LIMIT 1")
    suspend fun getByContentHash(hash: String): VehicleRevisionEntity?

    @Query("SELECT * FROM vehicle_revisions ORDER BY created_at_epoch_ms DESC")
    fun observeAll(): Flow<List<VehicleRevisionEntity>>

    @Query("SELECT COUNT(*) FROM vehicle_revisions")
    suspend fun count(): Int
}
