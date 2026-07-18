package com.elysium.vanguard.foundry.persistence.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.elysium.vanguard.foundry.persistence.entities.EngineeringArtifactEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for the `EngineeringArtifactEntity`.
 * The artifact's `contentHash` is the SHA-256
 * of the bytes; the bytes themselves are
 * stored in the content-addressed store
 * (skill 08, Phase 2 follow-up).
 */
@Dao
interface EngineeringArtifactDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(artifact: EngineeringArtifactEntity)

    @Update
    suspend fun update(artifact: EngineeringArtifactEntity): Int

    @Query("DELETE FROM engineering_artifacts WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT * FROM engineering_artifacts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): EngineeringArtifactEntity?

    @Query("SELECT * FROM engineering_artifacts WHERE content_hash = :hash LIMIT 1")
    suspend fun getByContentHash(hash: String): EngineeringArtifactEntity?

    @Query("SELECT * FROM engineering_artifacts WHERE subject_id = :subjectId")
    suspend fun getBySubject(subjectId: String): List<EngineeringArtifactEntity>

    @Query("SELECT * FROM engineering_artifacts ORDER BY created_at_epoch_ms DESC")
    fun observeAll(): Flow<List<EngineeringArtifactEntity>>

    @Query("SELECT COUNT(*) FROM engineering_artifacts")
    suspend fun count(): Int
}
