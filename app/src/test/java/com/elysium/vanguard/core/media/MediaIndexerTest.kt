package com.elysium.vanguard.core.media

import com.elysium.vanguard.core.database.media.MediaIndexDao
import com.elysium.vanguard.core.database.media.MediaIndexEntity
import com.elysium.vanguard.core.database.media.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 93 — the **Media Indexer Tests**,
 * the JVM tests for the incremental
 * indexing algorithm.
 *
 * The tests cover:
 *   - First scan (empty index → all
 *     discovered items are `Added`).
 *   - Subsequent scan with no changes
 *     (all items are `Unchanged`; the
 *     `lastSeenAtMs` is bumped).
 *   - Subsequent scan with new items
 *     (the new items are `Added`; the
 *     existing items are `Unchanged`).
 *   - Subsequent scan with updated items
 *     (the modified items are `Updated`;
 *     the unchanged items are
 *     `Unchanged`).
 *   - Subsequent scan with deleted items
 *     (the missing items are
 *     `Removed` after the grace period;
 *     the remaining items are
 *     `Unchanged`).
 *   - Multiple scans (the index grows
 *     incrementally; the `Added` count
 *     per scan is the diff).
 *   - IndexResult invariants (scanAtMs
 *     > 0; wasFirstScan is true on the
 *     first scan; the processed count
 *     matches).
 *   - MediaIndexerError invariants
 *     (code + message non-blank).
 *
 * The tests use a [FakeMediaIndexDao]
 * (the test seam for the DAO contract;
 * the test does NOT need the Room
 * machinery on the JVM).
 */
class MediaIndexerTest {

    // ============================================================
    // First scan (empty index)
    // ============================================================

    @Test
    fun first_scan_adds_all_discovered_items() = runBlocking {
        val source = InMemoryMediaSource().apply {
            add(image1())
            add(image2())
            add(audio1())
        }
        val dao = FakeMediaIndexDao()
        val indexer = DefaultMediaIndexer(dao = dao)
        val nowMs = 1_700_000_000_000L
        val result = indexer.scan(source, nowMs)
        // First scan: all 3 items are Added.
        assertEquals(3, result.added.size)
        assertEquals(0, result.updated.size)
        assertEquals(0, result.unchanged)
        assertEquals(0, result.removed.size)
        assertEquals(3, result.totalAfter)
        assertTrue(result.wasFirstScan)
        assertTrue(result.hasNewItems)
        // Every added item is in the DAO.
        assertEquals(3, dao.listAll().size)
    }

    // ============================================================
    // Subsequent scan with no changes
    // ============================================================

    @Test
    fun subsequent_scan_with_no_changes_is_all_unchanged() = runBlocking {
        val source = InMemoryMediaSource().apply {
            add(image1())
            add(audio1())
        }
        val dao = FakeMediaIndexDao()
        val indexer = DefaultMediaIndexer(dao = dao)
        // First scan.
        val t0 = 1_700_000_000_000L
        indexer.scan(source, t0)
        // Second scan at t1 = t0 + 1000.
        val t1 = t0 + 1_000L
        val result = indexer.scan(source, t1)
        // No changes: all 2 items are
        // Unchanged; 0 Added, 0 Updated,
        // 0 Removed.
        assertEquals(0, result.added.size)
        assertEquals(0, result.updated.size)
        assertEquals(2, result.unchanged)
        assertEquals(0, result.removed.size)
        assertEquals(2, result.totalAfter)
        assertFalse(result.wasFirstScan)
        // Every item's lastSeenAtMs was
        // bumped to t1.
        for (entity in dao.listAll()) {
            assertEquals(t1, entity.lastSeenAtMs)
        }
    }

    // ============================================================
    // Subsequent scan with new items
    // ============================================================

    @Test
    fun subsequent_scan_with_new_items_adds_only_the_new_ones() = runBlocking {
        val source = InMemoryMediaSource().apply {
            add(image1())
        }
        val dao = FakeMediaIndexDao()
        val indexer = DefaultMediaIndexer(dao = dao)
        val t0 = 1_700_000_000_000L
        indexer.scan(source, t0)
        // Add a new item to the source.
        source.add(image2())
        val t1 = t0 + 1_000L
        val result = indexer.scan(source, t1)
        // image1 is Unchanged; image2 is
        // Added.
        assertEquals(1, result.added.size)
        assertEquals(0, result.updated.size)
        assertEquals(1, result.unchanged)
        assertEquals(0, result.removed.size)
        assertEquals(2, result.totalAfter)
        assertTrue(result.hasNewItems)
        // The added item is the new one.
        val addedDisplayName = result.added[0].displayName
        assertTrue(
            "expected image2 displayName, got $addedDisplayName",
            addedDisplayName.contains("image2"),
        )
    }

    // ============================================================
    // Subsequent scan with updated items
    // ============================================================

    @Test
    fun subsequent_scan_with_updated_items_updates_only_the_changed_ones() = runBlocking {
        val source = InMemoryMediaSource().apply {
            add(image1())
            add(image2())
        }
        val dao = FakeMediaIndexDao()
        val indexer = DefaultMediaIndexer(dao = dao)
        val t0 = 1_700_000_000_000L
        indexer.scan(source, t0)
        // Modify image1 (mtime + size +
        // content hash all change).
        source.update(
            image1().copy(
                dateModifiedMs = t0 + 500L,
                sizeBytes = 99_999L,
                contentHash = "newhash",
            ),
        )
        val t1 = t0 + 1_000L
        val result = indexer.scan(source, t1)
        // image1 is Updated; image2 is
        // Unchanged.
        assertEquals(0, result.added.size)
        assertEquals(1, result.updated.size)
        assertEquals(1, result.unchanged)
        assertEquals(0, result.removed.size)
        // The updated item is image1.
        val updatedEntity = result.updated[0]
        assertTrue(
            "expected image1 displayName, got " +
                updatedEntity.displayName,
            updatedEntity.displayName.contains("image1"),
        )
        assertEquals(99_999L, updatedEntity.sizeBytes)
        assertEquals("newhash", updatedEntity.contentHash)
    }

    // ============================================================
    // Subsequent scan with deleted items (within grace period)
    // ============================================================

    @Test
    fun deletion_within_grace_period_is_not_removed() = runBlocking {
        val source = InMemoryMediaSource().apply {
            add(image1())
            add(image2())
        }
        val dao = FakeMediaIndexDao()
        val indexer = DefaultMediaIndexer(dao = dao)
        val t0 = 1_700_000_000_000L
        indexer.scan(source, t0)
        // Remove image2 from the source.
        source.removeById(mediaId = image2().mediaId)
        // Second scan shortly after t0
        // (within the 24h grace period).
        val t1 = t0 + 1_000L
        val result = indexer.scan(source, t1)
        // The deletion is NOT yet removed
        // (within the grace period).
        assertEquals(0, result.added.size)
        assertEquals(0, result.updated.size)
        assertEquals(1, result.unchanged)
        assertEquals(0, result.removed.size)
        // The index still has 2 items.
        assertEquals(2, dao.listAll().size)
    }

    @Test
    fun deletion_after_grace_period_is_removed() = runBlocking {
        val source = InMemoryMediaSource().apply {
            add(image1())
        }
        val dao = FakeMediaIndexDao()
        val indexer = DefaultMediaIndexer(dao = dao)
        val t0 = 1_700_000_000_000L
        indexer.scan(source, t0)
        // Remove image1.
        source.removeById(mediaId = image1().mediaId)
        // Second scan > 24h later (the
        // grace period is 24h).
        val t1 = t0 + (25L * 60L * 60L * 1000L)
        val result = indexer.scan(source, t1)
        // The deletion IS removed.
        assertEquals(1, result.removed.size)
        assertEquals(0, dao.listAll().size)
        assertEquals(0, result.totalAfter)
    }

    // ============================================================
    // Multiple scans (incremental)
    // ============================================================

    @Test
    fun multiple_scans_accumulate_incrementally() = runBlocking {
        val source = InMemoryMediaSource()
        val dao = FakeMediaIndexDao()
        val indexer = DefaultMediaIndexer(dao = dao)
        val t0 = 1_700_000_000_000L
        // Scan 1: empty source, 0 items.
        val r1 = indexer.scan(source, t0)
        assertEquals(0, r1.added.size)
        assertEquals(0, r1.totalAfter)
        // Scan 2: add 1 item.
        source.add(image1())
        val r2 = indexer.scan(source, t0 + 1_000L)
        assertEquals(1, r2.added.size)
        assertEquals(1, r2.totalAfter)
        // Scan 3: add another item.
        source.add(image2())
        val r3 = indexer.scan(source, t0 + 2_000L)
        assertEquals(1, r3.added.size)
        assertEquals(2, r3.totalAfter)
        // Scan 4: no new items.
        val r4 = indexer.scan(source, t0 + 3_000L)
        assertEquals(0, r4.added.size)
        assertEquals(0, r4.updated.size)
        assertEquals(2, r4.unchanged)
        assertEquals(2, r4.totalAfter)
    }

    // ============================================================
    // IndexResult invariants
    // ============================================================

    @Test
    fun indexResult_processed_count_matches_added_plus_updated_plus_unchanged() = runBlocking {
        val source = InMemoryMediaSource().apply {
            add(image1())
            add(image2())
        }
        val dao = FakeMediaIndexDao()
        val indexer = DefaultMediaIndexer(dao = dao)
        val t0 = 1_700_000_000_000L
        indexer.scan(source, t0)
        // Modify image2 (sizeBytes
        // changes) + add audio1.
        source.update(
            image2().copy(sizeBytes = 50_000L),
        )
        source.add(audio1())
        val r = indexer.scan(source, t0 + 1_000L)
        // image1 unchanged (1),
        // image2 updated (1),
        // audio1 added (1) → 3 total.
        assertEquals(3, r.processed)
        assertEquals(1, r.added.size)
        assertEquals(1, r.updated.size)
        assertEquals(1, r.unchanged)
    }

    // ============================================================
    // MediaIndexerError invariants
    // ============================================================

    @Test
    fun mediaIndexerError_subtypes_have_non_blank_code_and_message() {
        val errors: List<MediaIndexerError> = listOf(
            MediaIndexerError.SourceDiscoveryFailed(
                underlying = IllegalStateException("test"),
            ),
            MediaIndexerError.IndexWriteFailed(
                mediaId = 1L,
                underlying = IllegalStateException("test"),
            ),
            MediaIndexerError.InvalidNowMs(nowMs = 0L),
        )
        for (error in errors) {
            assertTrue(
                "expected non-blank code for " +
                    "${error::class.simpleName}",
                error.code.isNotBlank(),
            )
            assertTrue(
                "expected non-blank message for " +
                    "${error::class.simpleName}",
                error.message!!.isNotBlank(),
            )
        }
    }

    // ============================================================
    // Determinism
    // ============================================================

    @Test
    fun indexer_is_deterministic_for_the_same_source_and_nowMs() = runBlocking {
        val source1 = InMemoryMediaSource().apply {
            add(image1())
            add(audio1())
        }
        val source2 = InMemoryMediaSource().apply {
            add(image1())
            add(audio1())
        }
        val dao1 = FakeMediaIndexDao()
        val dao2 = FakeMediaIndexDao()
        val indexer1 = DefaultMediaIndexer(dao = dao1)
        val indexer2 = DefaultMediaIndexer(dao = dao2)
        val nowMs = 1_700_000_000_000L
        val r1 = indexer1.scan(source1, nowMs)
        val r2 = indexer2.scan(source2, nowMs)
        assertEquals(r1.added.size, r2.added.size)
        assertEquals(r1.updated.size, r2.updated.size)
        assertEquals(r1.unchanged, r2.unchanged)
        assertEquals(r1.removed.size, r2.removed.size)
        assertEquals(r1.totalAfter, r2.totalAfter)
        assertEquals(r1.wasFirstScan, r2.wasFirstScan)
    }

    // ============================================================
    // Helpers
    // ============================================================

    private fun image1(): DiscoveredMedia = DiscoveredMedia(
        mediaId = 1L,
        uri = "content://media/external/images/media/1",
        mediaType = MediaType.IMAGE,
        displayName = "image1.jpg",
        relativePath = "Pictures/",
        sizeBytes = 1_000L,
        dateModifiedMs = 1_700_000_000L,
        contentHash = "hash1",
    )

    private fun image2(): DiscoveredMedia = DiscoveredMedia(
        mediaId = 2L,
        uri = "content://media/external/images/media/2",
        mediaType = MediaType.IMAGE,
        displayName = "image2.jpg",
        relativePath = "Pictures/",
        sizeBytes = 2_000L,
        dateModifiedMs = 1_700_000_000L,
        contentHash = "hash2",
    )

    private fun audio1(): DiscoveredMedia = DiscoveredMedia(
        mediaId = 10L,
        uri = "content://media/external/audio/media/10",
        mediaType = MediaType.AUDIO,
        displayName = "track1.mp3",
        relativePath = "Music/",
        sizeBytes = 5_000_000L,
        dateModifiedMs = 1_700_000_000L,
        contentHash = "hashAudio1",
    )
}

/**
 * A fake [MediaIndexDao] for JVM tests.
 * The fake is a thin in-memory
 * implementation of the DAO contract
 * (the test does NOT need the Room
 * machinery; the fake is the canonical
 * test seam for the indexer's diff
 * algorithm).
 */
private class FakeMediaIndexDao : MediaIndexDao {

    private val rows: MutableList<MediaIndexEntity> =
        CopyOnWriteArrayList()
    private val countFlow: MutableStateFlow<Int> =
        MutableStateFlow(0)

    override suspend fun upsert(entity: MediaIndexEntity) {
        val index = rows.indexOfFirst { it.mediaId == entity.mediaId }
        if (index >= 0) {
            rows[index] = entity
        } else {
            rows.add(entity)
        }
        countFlow.value = rows.size
    }

    override suspend fun update(entity: MediaIndexEntity) {
        val index = rows.indexOfFirst { it.mediaId == entity.mediaId }
        if (index >= 0) {
            rows[index] = entity
            countFlow.value = rows.size
        }
    }

    override fun observeAll(): Flow<List<MediaIndexEntity>> =
        countFlow.map { rows.toList() }

    override suspend fun listAll(): List<MediaIndexEntity> =
        rows.toList()

    override suspend fun getById(mediaId: Long): MediaIndexEntity? =
        rows.firstOrNull { it.mediaId == mediaId }

    override suspend fun getByUri(uri: String): MediaIndexEntity? =
        rows.firstOrNull { it.uri == uri }

    override suspend fun listByType(mediaType: String): List<MediaIndexEntity> =
        rows.filter { it.mediaType == mediaType }

    override suspend fun listByRelativePath(relativePath: String): List<MediaIndexEntity> =
        rows.filter { it.relativePath == relativePath }

    override fun observeCount(): Flow<Int> = countFlow

    override suspend fun count(): Int = rows.size

    override suspend fun deleteById(mediaId: Long) {
        rows.removeAll { it.mediaId == mediaId }
        countFlow.value = rows.size
    }

    override suspend fun deleteStale(thresholdMs: Long): Int {
        val toRemove = rows.filter { it.lastSeenAtMs < thresholdMs }
        rows.removeAll(toRemove.toSet())
        countFlow.value = rows.size
        return toRemove.size
    }

    override suspend fun clear() {
        rows.clear()
        countFlow.value = 0
    }
}
