package com.elysium.vanguard.core.tasks

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledTaskDao {

    @Query("SELECT * FROM scheduled_tasks ORDER BY created_at DESC")
    fun observeAll(): Flow<List<ScheduledTaskEntity>>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :id")
    suspend fun byId(id: String): ScheduledTaskEntity?

    @Query("SELECT * FROM scheduled_tasks")
    suspend fun all(): List<ScheduledTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: ScheduledTaskEntity)

    @Query("DELETE FROM scheduled_tasks WHERE id = :id")
    suspend fun delete(id: String)
}
