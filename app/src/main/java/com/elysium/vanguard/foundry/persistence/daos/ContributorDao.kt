package com.elysium.vanguard.foundry.persistence.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.elysium.vanguard.foundry.persistence.entities.ContributorEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for the `ContributorEntity`. The
 * contributor's PII (the `email` field) is
 * stored as-is in Phase 1; the encryption at
 * rest is wired in Phase 5 (per skill 12).
 */
@Dao
interface ContributorDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(contributor: ContributorEntity)

    @Update
    suspend fun update(contributor: ContributorEntity): Int

    @Query("DELETE FROM contributors WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT * FROM contributors WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ContributorEntity?

    @Query("SELECT * FROM contributors WHERE email = :email LIMIT 1")
    suspend fun getByEmail(email: String): ContributorEntity?

    @Query("SELECT * FROM contributors ORDER BY display_name ASC")
    fun observeAll(): Flow<List<ContributorEntity>>

    @Query("SELECT COUNT(*) FROM contributors")
    suspend fun count(): Int
}
