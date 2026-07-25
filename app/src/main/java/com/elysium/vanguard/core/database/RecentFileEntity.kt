package com.elysium.vanguard.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * PHASE 139 — a recently-accessed file. The body of the
 * proprietary Windows desktop (Phase 121) ships a real
 * file manager with breadcrumb + tap-to-navigate. Phase
 * 139 adds a persistent "recent files" list: every time
 * the user opens a file, the [RecentFileRepository]
 * records the access. The Files body shows a "Recent"
 * section at the top so the user can re-open files they
 * were just working on without having to navigate the
 * whole tree.
 *
 * The table is keyed by [path] (the unique primary key)
 * so re-opening the same file just updates [lastOpenedAt]
 * + [openCount]. No UPSERT race; we use `INSERT OR
 * REPLACE` and a single atomic write per access.
 */
@Entity(tableName = "recent_files")
data class RecentFileEntity(
    @PrimaryKey val path: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "mime_type") val mimeType: String? = null,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long,
    @ColumnInfo(name = "open_count") val openCount: Int = 1,
)

@Dao
interface RecentFileDao {
    /**
     * Record (or refresh) a recent-file entry. SQLite
     * `ON CONFLICT REPLACE` overwrites the row, so we
     * have to preserve the old [openCount] by selecting
     * first. Doing it in a single SQL statement via
     * `INSERT ... ON CONFLICT(path) DO UPDATE` keeps it
     * atomic.
     */
    @Query(
        """
        INSERT INTO recent_files
            (path, display_name, size_bytes, mime_type, last_opened_at, open_count)
        VALUES
            (:path, :displayName, :sizeBytes, :mimeType, :lastOpenedAt, 1)
        ON CONFLICT(path) DO UPDATE SET
            display_name = excluded.display_name,
            size_bytes = excluded.size_bytes,
            mime_type = excluded.mime_type,
            last_opened_at = excluded.last_opened_at,
            open_count = recent_files.open_count + 1
        """
    )
    suspend fun upsert(
        path: String,
        displayName: String,
        sizeBytes: Long,
        mimeType: String?,
        lastOpenedAt: Long,
    )

    /** Stream the N most-recent files, newest first. */
    @Query("SELECT * FROM recent_files ORDER BY last_opened_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RecentFileEntity>>

    /** All recent files, newest first (no limit). */
    @Query("SELECT * FROM recent_files ORDER BY last_opened_at DESC")
    fun observeAll(): Flow<List<RecentFileEntity>>

    @Query("SELECT * FROM recent_files WHERE path = :path LIMIT 1")
    suspend fun getByPath(path: String): RecentFileEntity?

    @Query("DELETE FROM recent_files WHERE path = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM recent_files")
    suspend fun clear()

    /**
     * Keep only the [keep] most-recent files. Used by the
     * `recordAccess` path to enforce a hard cap on the
     * list (e.g. 50 entries).
     */
    @Query(
        """
        DELETE FROM recent_files
        WHERE path NOT IN (
            SELECT path FROM recent_files
            ORDER BY last_opened_at DESC
            LIMIT :keep
        )
        """
    )
    suspend fun trimTo(keep: Int)

    @Query("SELECT COUNT(*) FROM recent_files")
    suspend fun count(): Int
}
