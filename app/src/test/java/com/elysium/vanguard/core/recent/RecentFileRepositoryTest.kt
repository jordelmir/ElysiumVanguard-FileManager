package com.elysium.vanguard.core.recent

import com.elysium.vanguard.core.database.RecentFileDao
import com.elysium.vanguard.core.database.RecentFileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PHASE 139 — RecentFileRepository unit tests.
 *
 * Uses an in-memory [RecentFileDao] stub (no Android
 * Room / no Robolectric) so the repository's business
 * logic — timestamp stamping, upsert increments, FIFO
 * trim — can be verified in a plain JVM test.
 *
 * What we cover:
 *  - First record creates a new row with `open_count = 1`
 *  - Re-recording the same path increments `open_count`
 *  - `recordAccess` sorts by `last_opened_at` DESC
 *  - `trimTo(MAX_RECENT)` keeps the N most-recent and
 *    deletes the rest (FIFO)
 *  - `clear` removes everything
 *  - `deleteByPath` removes just that one row
 *  - `observeRecent` reflects changes in the underlying
 *    state without polling
 */
class RecentFileRepositoryTest {

    private lateinit var dao: InMemoryRecentFileDao
    private lateinit var repo: RecentFileRepository
    private var fakeNow: Long = 1_000_000L

    @Before fun setUp() {
        dao = InMemoryRecentFileDao()
        repo = RecentFileRepository(dao, now = { fakeNow })
    }

    @Test fun `first record creates a new row with open_count of 1`() = runBlocking {
        repo.recordAccess(
            path = "/sdcard/Documents/notes.txt",
            displayName = "notes.txt",
            sizeBytes = 4096L,
        )
        val entry = dao.getByPath("/sdcard/Documents/notes.txt")
        assertEquals("notes.txt", entry?.displayName)
        assertEquals(4096L, entry?.sizeBytes)
        assertEquals(1, entry?.openCount)
        assertEquals(1_000_000L, entry?.lastOpenedAt)
    }

    @Test fun `re-recording the same path increments open_count and refreshes timestamp`() = runBlocking {
        repo.recordAccess("/sdcard/a.txt", "a.txt", 100L)
        fakeNow = 1_000_500L
        repo.recordAccess("/sdcard/a.txt", "a.txt", 100L)
        val entry = dao.getByPath("/sdcard/a.txt")!!
        assertEquals(2, entry.openCount)
        assertEquals(1_000_500L, entry.lastOpenedAt)
    }

    @Test fun `recordAccess sorts by last_opened_at descending`() = runBlocking {
        repo.recordAccess("/sdcard/a.txt", "a.txt", 0L)
        fakeNow = 2_000_000L
        repo.recordAccess("/sdcard/b.txt", "b.txt", 0L)
        fakeNow = 3_000_000L
        repo.recordAccess("/sdcard/c.txt", "c.txt", 0L)
        val all = repo.observeAll().first()
        assertEquals(listOf("c.txt", "b.txt", "a.txt"), all.map { it.displayName })
    }

    @Test fun `trimTo caps the list at MAX_RECENT with FIFO eviction`() = runBlocking {
        // Insert 60 distinct paths; MAX_RECENT = 50 so the oldest 10 are dropped.
        repeat(60) { i ->
            fakeNow = 1_000_000L + i
            repo.recordAccess(
                path = "/sdcard/file_$i.txt",
                displayName = "file_$i.txt",
                sizeBytes = 0L,
            )
        }
        val all = repo.observeAll().first()
        assertEquals(50, all.size)
        // The first 10 are gone; the rest remain.
        assertTrue("file_0 should be evicted", dao.getByPath("/sdcard/file_0.txt") == null)
        assertTrue("file_9 should be evicted", dao.getByPath("/sdcard/file_9.txt") == null)
        assertTrue("file_10 should remain", dao.getByPath("/sdcard/file_10.txt") != null)
        assertTrue("file_59 should remain (newest)", dao.getByPath("/sdcard/file_59.txt") != null)
    }

    @Test fun `clear removes every row`() = runBlocking {
        repeat(5) { i ->
            repo.recordAccess("/sdcard/file_$i.txt", "file_$i.txt", 0L)
        }
        assertEquals(5, repo.count())
        repo.clear()
        assertEquals(0, repo.count())
    }

    @Test fun `deleteByPath removes just that one row`() = runBlocking {
        repo.recordAccess("/sdcard/a.txt", "a.txt", 0L)
        repo.recordAccess("/sdcard/b.txt", "b.txt", 0L)
        repo.recordAccess("/sdcard/c.txt", "c.txt", 0L)
        repo.deleteByPath("/sdcard/b.txt")
        assertEquals(2, repo.count())
        assertNull(dao.getByPath("/sdcard/b.txt"))
        assertTrue(dao.getByPath("/sdcard/a.txt") != null)
        assertTrue(dao.getByPath("/sdcard/c.txt") != null)
    }

    @Test fun `recordAccessWith uses the supplied timestamp not now`() = runBlocking {
        repo.recordAccessWith(
            path = "/sdcard/x.txt",
            displayName = "x.txt",
            sizeBytes = 0L,
            atMillis = 42_000L,
        )
        assertEquals(42_000L, dao.getByPath("/sdcard/x.txt")?.lastOpenedAt)
    }

    @Test fun `observeRecent with limit caps the stream`() = runBlocking {
        repeat(20) { i ->
            fakeNow = 1_000_000L + i
            repo.recordAccess("/sdcard/file_$i.txt", "file_$i.txt", 0L)
        }
        val recent = repo.observeRecent(limit = 5).first()
        assertEquals(5, recent.size)
        // Newest first.
        assertEquals("file_19.txt", recent[0].displayName)
        assertEquals("file_15.txt", recent[4].displayName)
    }

    @Test fun `repeated accesses move the same path to the top each time`() = runBlocking {
        repo.recordAccess("/sdcard/old.txt", "old.txt", 0L)
        fakeNow = 1_000_001L
        repo.recordAccess("/sdcard/mid.txt", "mid.txt", 0L)
        fakeNow = 1_000_002L
        // Re-access the old file — it should jump to the top.
        repo.recordAccess("/sdcard/old.txt", "old.txt", 0L)
        val all = repo.observeAll().first()
        assertEquals("old.txt", all[0].displayName)
        assertEquals("mid.txt", all[1].displayName)
    }
}

// --- Test doubles -------------------------------------------------------

/**
 * Minimal in-memory [RecentFileDao] stub. The production
 * DAO uses Room + SQL; the in-memory variant is a plain
 * snapshot list. The [MutableStateFlow] backing the
 * `observe*` methods lets the repository see the same
 * reactive contract without the database dependency.
 */
internal class InMemoryRecentFileDao : RecentFileDao {
    private val rows = mutableMapOf<String, RecentFileEntity>()
    private val flow = MutableStateFlow<List<RecentFileEntity>>(emptyList())
    private val lock = Any()

    private fun emit() {
        flow.value = synchronized(lock) { rows.values.sortedByDescending { it.lastOpenedAt } }
    }

    override suspend fun upsert(
        path: String,
        displayName: String,
        sizeBytes: Long,
        mimeType: String?,
        lastOpenedAt: Long,
    ) = synchronized(lock) {
        val existing = rows[path]
        val merged = RecentFileEntity(
            path = path,
            displayName = displayName,
            sizeBytes = sizeBytes,
            mimeType = mimeType,
            lastOpenedAt = lastOpenedAt,
            openCount = (existing?.openCount ?: 0) + 1,
        )
        rows[path] = merged
        emit()
    }

    override fun observeRecent(limit: Int): Flow<List<RecentFileEntity>> =
        kotlinx.coroutines.flow.flow {
            flow.collect { value -> this@flow.emit(value.take(limit)) }
        }

    override fun observeAll(): Flow<List<RecentFileEntity>> = flow

    override suspend fun getByPath(path: String): RecentFileEntity? = synchronized(lock) {
        rows[path]
    }

    override suspend fun deleteByPath(path: String) = synchronized(lock) {
        rows.remove(path)
        emit()
    }

    override suspend fun clear() = synchronized(lock) {
        rows.clear()
        emit()
    }

    override suspend fun trimTo(keep: Int) = synchronized(lock) {
        val sorted = rows.values.sortedByDescending { it.lastOpenedAt }
        val evict = sorted.drop(keep).map { it.path }.toSet()
        rows.keys.retainAll { it !in evict }
        emit()
    }

    override suspend fun count(): Int = synchronized(lock) { rows.size }
}
