package com.elysium.vanguard.foundry.persistence.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.elysium.vanguard.foundry.persistence.entities.ProvenanceRecordEntity

/**
 * Room DAO for the `ProvenanceRecordEntity`.
 * The record is **append-only** (per
 * ADR-0006) — the DAO only has `insert`
 * (no `update` + no `delete`). The
 * audit-trail persistence is the source
 * of truth; the `provenance_records` table
 * is the read-side cache.
 */
@Dao
interface ProvenanceRecordDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: ProvenanceRecordEntity)

    @Query("SELECT * FROM provenance_records WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ProvenanceRecordEntity?

    @Query("SELECT * FROM provenance_records WHERE subject_id = :subjectId ORDER BY created_at_epoch_ms ASC")
    suspend fun getBySubject(subjectId: String): List<ProvenanceRecordEntity>

    @Query("SELECT COUNT(*) FROM provenance_records")
    suspend fun count(): Int
}
