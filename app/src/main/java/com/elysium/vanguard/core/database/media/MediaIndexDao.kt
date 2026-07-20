package com.elysium.vanguard.core.database.media

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Phase 93 — the **Media Index DAO**, the
 * typed data access layer for the Elysium
 * media index.
 *
 * The DAO is the **only seam** the indexer
 * uses to read + write the index. The DAO
 * is the **typed contract** between the
 * indexer's domain logic and Room's storage
 * primitives.
 *
 * The DAO is **per-method typed**: every
 * method has a typed return + typed
 * parameters. A free-form `Cursor` or
 * untyped `Map<String, Any>` is never the
 * value of a method (per
 * `.ai/AGENTS.md` 24.1).
 */
@Dao
interface MediaIndexDao {

    /**
     * Insert or replace an entry. The
     * conflict strategy is `REPLACE` (the
     * canonical "upsert" pattern; a media
     * item that already exists is replaced
     * with the new version).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MediaIndexEntity)

    /**
     * Update an existing entry. The
     * entry must already exist (the
     * update is no-op for unknown media
     * ids; the consumer calls `upsert`
     * for new entries).
     */
    @Update
    suspend fun update(entity: MediaIndexEntity)

    /**
     * Observe all entries (a Flow for
     * Compose-driven UI). The list is
     * sorted by `lastSeenAtMs DESC` (most
     * recently seen first).
     */
    @Query("SELECT * FROM media_index ORDER BY last_seen_at_ms DESC")
    fun observeAll(): Flow<List<MediaIndexEntity>>

    /**
     * List all entries (a one-shot
     * query, not a Flow). Used by the
     * indexer to compute the diff on a
     * fresh scan.
     */
    @Query("SELECT * FROM media_index ORDER BY last_seen_at_ms DESC")
    suspend fun listAll(): List<MediaIndexEntity>

    /**
     * Get an entry by media id. Returns
     * `null` if the id is not in the
     * index.
     */
    @Query("SELECT * FROM media_index WHERE media_id = :mediaId LIMIT 1")
    suspend fun getById(mediaId: Long): MediaIndexEntity?

    /**
     * Get an entry by URI. Returns
     * `null` if the URI is not in the
     * index. Used by the indexer to
     * check whether a discovered file
     * was already seen.
     */
    @Query("SELECT * FROM media_index WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): MediaIndexEntity?

    /**
     * List entries by media type
     * (IMAGE / VIDEO / AUDIO). Used by
     * the MEDIA VAULT to show only
     * images + videos; the AUDIO HUB
     * to show only audio. The list is
     * sorted by `lastSeenAtMs DESC`
     * (most recently seen first).
     */
    @Query(
        "SELECT * FROM media_index WHERE media_type = :mediaType " +
            "ORDER BY last_seen_at_ms DESC",
    )
    suspend fun listByType(mediaType: String): List<MediaIndexEntity>

    /**
     * List entries by relative path.
     * Used by the MEDIA VAULT to show
     * only the items in a specific
     * album (the relative path is the
     * "album" identifier).
     */
    @Query(
        "SELECT * FROM media_index WHERE relative_path = :relativePath " +
            "ORDER BY last_seen_at_ms DESC",
    )
    suspend fun listByRelativePath(relativePath: String): List<MediaIndexEntity>

    /**
     * Observe the count of entries. A
     * Flow so the UI can react to
     * changes (a new scan + new items
     * → the count goes up; the UI
     * re-renders the "X items" badge).
     */
    @Query("SELECT COUNT(*) FROM media_index")
    fun observeCount(): Flow<Int>

    /**
     * The one-shot count. Used by the
     * indexer to detect "first scan"
     * (count == 0 → no previous scan).
     */
    @Query("SELECT COUNT(*) FROM media_index")
    suspend fun count(): Int

    /**
     * Delete an entry by media id. Used
     * by the indexer when a file is
     * missing from the device (the file
     * was deleted between scans).
     */
    @Query("DELETE FROM media_index WHERE media_id = :mediaId")
    suspend fun deleteById(mediaId: Long)

    /**
     * Delete all entries whose
     * `lastSeenAtMs` is strictly less
     * than the threshold. The indexer
     * uses this to garbage-collect
     * deleted files (a file that wasn't
     * seen in the latest scan and has a
     * `lastSeenAtMs` older than the
     * threshold is considered gone).
     */
    @Query("DELETE FROM media_index WHERE last_seen_at_ms < :thresholdMs")
    suspend fun deleteStale(thresholdMs: Long): Int

    /**
     * Clear the entire index. Used by
     * the "force re-scan" UI action
     * (the user wants to start from
     * scratch).
     */
    @Query("DELETE FROM media_index")
    suspend fun clear()
}
