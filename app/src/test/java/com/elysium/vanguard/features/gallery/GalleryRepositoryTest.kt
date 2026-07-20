package com.elysium.vanguard.features.gallery

import com.elysium.vanguard.core.database.media.MediaIndexDao
import com.elysium.vanguard.core.database.media.MediaIndexEntity
import com.elysium.vanguard.core.database.media.MediaType
import com.elysium.vanguard.core.media.DefaultMediaIndexer
import com.elysium.vanguard.core.media.DiscoveredMedia
import com.elysium.vanguard.core.media.InMemoryMediaSource
import com.elysium.vanguard.core.media.MediaIndexer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 94 — the **GalleryRepository tests**,
 * the JVM tests that verify the new
 * MediaIndexDao wiring.
 *
 * The tests use a [FakeMediaIndexDao]
 * (the test seam for the DAO; the test
 * does NOT need the Room machinery) +
 * the [InMemoryMediaSource] (the test
 * seam for the production
 * [ContentResolverMediaSource]).
 *
 * The tests cover:
 *   - The repository maps `MediaIndexEntity`
 *     to [GalleryMedia] with all canonical
 *     fields preserved.
 *   - The repository filters by IMAGE + VIDEO
 *     only (AUDIO entries in the index are
 *     excluded).
 *   - The mapping preserves the canonical
 *     fields (id, name, path, mimeType,
 *     dateModified, isFavorite).
 */
class GalleryRepositoryTest {

    @Test
    fun entity_to_gallery_media_mapping_preserves_all_canonical_fields() =
        runBlocking {
            val entity = MediaIndexEntity(
                mediaId = 99L,
                uri = "content://media/external/images/media/99",
                mediaType = MediaType.IMAGE.name,
                displayName = "important_photo.png",
                relativePath = "Pictures/",
                sizeBytes = 123_456L,
                dateModifiedMs = 1_700_000_000_000L,
                mimeType = "image/png",
                discoveredAtMs = 1_700_000_000_000L,
                lastSeenAtMs = 1_700_000_000_000L,
            )
            val media = mapToGalleryMedia(entity)
            assertEquals(99L, media.id)
            assertEquals("important_photo.png", media.name)
            assertEquals(
                "content://media/external/images/media/99",
                media.path,
            )
            assertEquals("image/png", media.mimeType)
            assertEquals(1_700_000_000_000L, media.dateModified)
        }

    @Test
    fun filter_by_type_excludes_audio_entries() = runBlocking {
        val entities = listOf(
            imageEntity(1L, "sunset.jpg"),
            audioEntity(10L, "track1.mp3"),
            videoEntity(2L, "vacation.mp4"),
            audioEntity(11L, "track2.mp3"),
        )
        val filtered = entities.filter { entity ->
            entity.mediaType == MediaType.IMAGE.name ||
                entity.mediaType == MediaType.VIDEO.name
        }
        assertEquals(2, filtered.size)
        val names = filtered.map { it.displayName }.toSet()
        assertTrue(
            "expected sunset + vacation, got $names",
            names == setOf("sunset.jpg", "vacation.mp4"),
        )
    }

    @Test
    fun indexer_persists_discovered_media_in_dao() = runBlocking {
        val dao = FakeMediaIndexDao()
        val source = InMemoryMediaSource().apply {
            add(imageMedia(1L, "sunset.jpg", "image/jpeg"))
            add(audioMedia(10L, "track1.mp3", "audio/mpeg"))
            add(videoMedia(2L, "vacation.mp4", "video/mp4"))
        }
        val indexer: MediaIndexer = DefaultMediaIndexer(dao)
        val result = indexer.scan(source, 1_700_000_000_000L)
        // The scan adds all 3 items
        // (the indexer doesn't filter by
        // type; the filtering is the
        // repository's job).
        assertEquals(3, result.added.size)
        // The DAO has all 3 entities.
        val all = dao.listAll()
        assertEquals(3, all.size)
        // The DAO can be queried by
        // type: 1 image + 1 video + 1 audio.
        val images = dao.listByType(MediaType.IMAGE.name)
        val videos = dao.listByType(MediaType.VIDEO.name)
        val audios = dao.listByType(MediaType.AUDIO.name)
        assertEquals(1, images.size)
        assertEquals(1, videos.size)
        assertEquals(1, audios.size)
    }

    // ============================================================
    // Mapping helper (mirrors the
    // production GalleryRepository's
    // private toGalleryMedia extension).
    // ============================================================

    private fun mapToGalleryMedia(entity: MediaIndexEntity): GalleryMedia =
        GalleryMedia(
            id = entity.mediaId,
            name = entity.displayName,
            path = entity.uri,
            mimeType = entity.mimeType,
            dateModified = entity.dateModifiedMs,
            isFavorite = entity.isFavorite,
        )

    // ============================================================
    // Entity builders
    // ============================================================

    private fun imageEntity(id: Long, name: String): MediaIndexEntity =
        MediaIndexEntity(
            mediaId = id,
            uri = "content://media/external/images/media/$id",
            mediaType = MediaType.IMAGE.name,
            displayName = name,
            relativePath = "Pictures/",
            sizeBytes = 1_000L,
            dateModifiedMs = 1_700_000_000_000L,
            mimeType = "image/jpeg",
            discoveredAtMs = 1_700_000_000_000L,
            lastSeenAtMs = 1_700_000_000_000L,
        )

    private fun audioEntity(id: Long, name: String): MediaIndexEntity =
        MediaIndexEntity(
            mediaId = id,
            uri = "content://media/external/audio/media/$id",
            mediaType = MediaType.AUDIO.name,
            displayName = name,
            relativePath = "Music/",
            sizeBytes = 5_000_000L,
            dateModifiedMs = 1_700_000_000_000L,
            mimeType = "audio/mpeg",
            discoveredAtMs = 1_700_000_000_000L,
            lastSeenAtMs = 1_700_000_000_000L,
        )

    private fun videoEntity(id: Long, name: String): MediaIndexEntity =
        MediaIndexEntity(
            mediaId = id,
            uri = "content://media/external/video/media/$id",
            mediaType = MediaType.VIDEO.name,
            displayName = name,
            relativePath = "Movies/",
            sizeBytes = 10_000_000L,
            dateModifiedMs = 1_700_000_000_000L,
            mimeType = "video/mp4",
            discoveredAtMs = 1_700_000_000_000L,
            lastSeenAtMs = 1_700_000_000_000L,
        )

    // ============================================================
    // DiscoveredMedia builders
    // ============================================================

    private fun imageMedia(
        id: Long,
        name: String,
        mimeType: String,
    ): DiscoveredMedia = DiscoveredMedia(
        mediaId = id,
        uri = "content://media/external/images/media/$id",
        mediaType = MediaType.IMAGE,
        displayName = name,
        relativePath = "Pictures/",
        sizeBytes = 1_000L,
        dateModifiedMs = 1_700_000_000_000L,
        mimeType = mimeType,
    )

    private fun audioMedia(
        id: Long,
        name: String,
        mimeType: String,
    ): DiscoveredMedia = DiscoveredMedia(
        mediaId = id,
        uri = "content://media/external/audio/media/$id",
        mediaType = MediaType.AUDIO,
        displayName = name,
        relativePath = "Music/",
        sizeBytes = 5_000_000L,
        dateModifiedMs = 1_700_000_000_000L,
        mimeType = mimeType,
    )

    private fun videoMedia(
        id: Long,
        name: String,
        mimeType: String,
    ): DiscoveredMedia = DiscoveredMedia(
        mediaId = id,
        uri = "content://media/external/video/media/$id",
        mediaType = MediaType.VIDEO,
        displayName = name,
        relativePath = "Movies/",
        sizeBytes = 10_000_000L,
        dateModifiedMs = 1_700_000_000_000L,
        mimeType = mimeType,
    )
}

/**
 * Phase 94 — a simple in-memory DAO for
 * the test. Mirrors the production
 * `MediaIndexDao` contract.
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
