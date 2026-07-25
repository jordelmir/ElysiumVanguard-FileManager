package com.elysium.vanguard.core.recent

import com.elysium.vanguard.core.database.RecentFileDao
import com.elysium.vanguard.core.database.RecentFileEntity
import kotlinx.coroutines.flow.Flow

/**
 * PHASE 139 — the recent-files repository.
 *
 * Wraps [RecentFileDao] with a tiny business-logic layer:
 *  - Stamps `lastOpenedAt` (no clock injection; we use
 *    `System.currentTimeMillis()` and let tests use
 *    [recordAccessWith] for determinism).
 *  - Enforces a hard cap (`MAX_RECENT = 50`) so the table
 *    doesn't grow unbounded.
 *  - Surfaces typed `Flow` queries that the Files body
 *    collects.
 *
 * The repository is intentionally `@Singleton`-light
 * (no Hilt) — it's the only place that touches the
 * recent_files table, and the Files body is the only
 * consumer. Direct injection is fine.
 */
class RecentFileRepository(
    private val dao: RecentFileDao,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    fun observeRecent(limit: Int = 50): Flow<List<RecentFileEntity>> =
        dao.observeRecent(limit)

    fun observeAll(): Flow<List<RecentFileEntity>> = dao.observeAll()

    suspend fun getByPath(path: String): RecentFileEntity? = dao.getByPath(path)

    /**
     * Record that the user opened [path] at [now]. Trims
     * the table to [MAX_RECENT] entries so the list never
     * grows unbounded. Called from [RealFilesBody] when
     * the user taps a file.
     */
    suspend fun recordAccess(
        path: String,
        displayName: String,
        sizeBytes: Long,
        mimeType: String? = null,
    ) {
        dao.upsert(
            path = path,
            displayName = displayName,
            sizeBytes = sizeBytes,
            mimeType = mimeType,
            lastOpenedAt = now(),
        )
        dao.trimTo(MAX_RECENT)
    }

    /**
     * Test seam — same as [recordAccess] but the caller
     * supplies the wall-clock. Production code should
     * never call this.
     */
    suspend fun recordAccessWith(
        path: String,
        displayName: String,
        sizeBytes: Long,
        mimeType: String? = null,
        atMillis: Long,
    ) {
        dao.upsert(
            path = path,
            displayName = displayName,
            sizeBytes = sizeBytes,
            mimeType = mimeType,
            lastOpenedAt = atMillis,
        )
        dao.trimTo(MAX_RECENT)
    }

    suspend fun deleteByPath(path: String) = dao.deleteByPath(path)

    suspend fun clear() = dao.clear()

    suspend fun count(): Int = dao.count()

    companion object {
        const val MAX_RECENT: Int = 50
    }
}
