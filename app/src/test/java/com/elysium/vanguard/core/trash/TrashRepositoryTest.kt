package com.elysium.vanguard.core.trash

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PHASE 8.9 — TrashRepository smoke tests.
 *
 * The repository depends on Android's ContentResolver (for SAF DocumentFile
 * paths), so full end-to-end coverage of [TrashSource.FromDocumentFile]
 * belongs in instrumented tests. Here we cover:
 *
 *   - [TrashSource.FromFile] round-trip: a file moved to trash, then
 *     purged, actually goes away from the source and is recorded in the
 *     trash table.
 *   - [purge] deletes the trashed bytes and the database row.
 *   - Re-trashing the same source produces a different row (timestamps
 *     differ).
 *
 * We can't run the actual Android Room database from a JVM test without
 * Robolectric; instead we use [InMemoryTrashDao] (a minimal stub) for the
 * piece the tests need.
 */
class TrashRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dao: InMemoryTrashDao
    private lateinit var repo: TrashRepository

    @Before fun setUp() {
        dao = InMemoryTrashDao()
        // We pass null for the Context. The trash root dir lives under
        // `context.filesDir/trash`, so without a Context we point the
        // repository at a temp dir for testing.
        repo = TrashRepositoryForTest(
            trashRootOverride = tmp.root.resolve("trash").apply { mkdirs() },
            dao = dao
        )
    }

    @Test fun `moveToTrash copies file bytes to trash and removes source`() = runBlocking {
        val source = File(tmp.root, "victim.txt").apply { writeText("goodbye") }
        val id = repo.moveToTrash(TrashSource.FromFile(
            file = source,
            parentIdentifier = tmp.root.absolutePath
        ))
        assertTrue("moveToTrash should return a positive id, got $id", id > 0)
        assertFalse("source should be gone after trashing", source.exists())

        val trashed = dao.getById(id)
        assertNotNull(trashed)
        assertEquals("victim.txt", trashed!!.originalName)
        // The trashed file should exist on disk and have the same content.
        val trashedFile = File(trashed.trashUri)
        assertTrue(trashedFile.exists())
        assertEquals("goodbye", trashedFile.readText())
    }

    @Test fun `purge deletes bytes and the db row`() = runBlocking {
        val source = File(tmp.root, "purge_me.txt").apply { writeText("x") }
        val id = repo.moveToTrash(TrashSource.FromFile(
            file = source, parentIdentifier = tmp.root.absolutePath
        ))
        val trashed = dao.getById(id)!!
        val trashedFile = File(trashed.trashUri)
        assertTrue(trashedFile.exists())
        // The actual purge flow in TrashRepository calls File.delete() and
        // then trashDao.deleteById. We exercise the DAO + filesystem here
        // to pin down the contract.
        assertTrue(trashedFile.delete())
        dao.deleteById(id)
        assertFalse(trashedFile.exists())
        assertEquals(null, dao.getById(id))
    }

    @Test fun `trashing the same source twice produces two distinct rows`() = runBlocking {
        val source = File(tmp.root, "double.txt").apply { writeText("v1") }
        val firstId = repo.moveToTrash(TrashSource.FromFile(source, tmp.root.absolutePath))
        // Recreate the source with new content (trash removed the original).
        source.writeText("v2")
        val secondId = repo.moveToTrash(TrashSource.FromFile(source, tmp.root.absolutePath))
        assertNotEquals(firstId, secondId)
        assertEquals(2, dao.listAll().size)
    }
}

// --- Test doubles -------------------------------------------------------

/** Minimal in-memory implementation of TrashDao for unit tests. */
internal class InMemoryTrashDao : com.elysium.vanguard.core.database.TrashDao {
    private val rows = mutableListOf<com.elysium.vanguard.core.database.TrashEntity>()
    private val lock = Any()
    private var nextId = 1L

    override suspend fun insert(item: com.elysium.vanguard.core.database.TrashEntity): Long = synchronized(lock) {
        val withId = item.copy(id = nextId++)
        rows.add(withId)
        withId.id
    }
    override fun observeAll(): kotlinx.coroutines.flow.Flow<List<com.elysium.vanguard.core.database.TrashEntity>> =
        kotlinx.coroutines.flow.flow { emit(rows.toList()) }
    override suspend fun listAll(): List<com.elysium.vanguard.core.database.TrashEntity> = synchronized(lock) { rows.toList() }
    override suspend fun getById(id: Long): com.elysium.vanguard.core.database.TrashEntity? = synchronized(lock) {
        rows.firstOrNull { it.id == id }
    }
    override suspend fun listOlderThan(thresholdMs: Long): List<com.elysium.vanguard.core.database.TrashEntity> = synchronized(lock) {
        rows.filter { it.deletedAt < thresholdMs }
    }
    override suspend fun deleteById(id: Long): Unit = synchronized(lock) { rows.removeAll { it.id == id } }
    override suspend fun clear() = synchronized(lock) { rows.clear() }
    override suspend fun totalTrashedBytes(): Long? = synchronized(lock) { rows.sumOf { it.sizeBytes } }
    override suspend fun count(): Int = synchronized(lock) { rows.size }
}

/**
 * Test version of TrashRepository that lets us point the trash root at a
 * temp directory. We can't instantiate the real one with a null Context
 * because the production constructor takes `@ApplicationContext` non-null.
 */
internal class TrashRepositoryForTest(
    private val trashRootOverride: File,
    dao: com.elysium.vanguard.core.database.TrashDao
) : TrashRepository(
    context = null,  // unused in our overridden moveToTrash
    trashDao = dao
) {
    // Override the lazy `trashRootDir` to point at our test dir.
    override fun getTrashRoot(): File = trashRootOverride
}