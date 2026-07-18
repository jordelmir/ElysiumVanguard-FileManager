package com.elysium.vanguard.foundry.persistence.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.elysium.vanguard.foundry.persistence.entities.ProjectEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for the `ProjectEntity`. The DAO
 * exposes:
 *   - `insert` / `update`: the mutations
 *     (the optimistic-concurrency check is in
 *     the repository layer).
 *   - `getById`: a single-shot read.
 *   - `observeAll`: a `Flow` for reactive UI.
 *
 * The DAO is the only path to read + write
 * the `projects` table (per ADR-0006 + the
 * Foundry's "one owner, many readers" rule).
 */
@Dao
interface ProjectDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(project: ProjectEntity)

    @Update
    suspend fun update(project: ProjectEntity): Int

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE owner_id = :ownerId")
    suspend fun getByOwner(ownerId: String): List<ProjectEntity>

    @Query("SELECT * FROM projects ORDER BY name ASC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT COUNT(*) FROM projects")
    suspend fun count(): Int
}
