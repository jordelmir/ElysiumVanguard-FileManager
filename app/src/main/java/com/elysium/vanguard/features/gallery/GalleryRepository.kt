package com.elysium.vanguard.features.gallery

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.elysium.vanguard.core.database.media.MediaIndexDao
import com.elysium.vanguard.core.database.media.MediaIndexEntity
import com.elysium.vanguard.core.database.media.MediaType
import com.elysium.vanguard.core.media.ContentResolverMediaSource
import com.elysium.vanguard.core.media.MediaIndexer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 94 — the **Gallery Repository** rewired
 * to use the persistent Elysium media index.
 *
 * Per the master vision's "MEDIA VAULT" portal
 * item + the user's direct ask ("haz que escanee
 * las imágenes, apenas una entre guárdala
 * local, y solo suma lo nuevo en futuros
 * escaneos"): the repository now reads from
 * the persistent `MediaIndexDao` instead of
 * re-querying `MediaStore` on every screen
 * visit. The first collect triggers an
 * incremental scan; subsequent collects
 * observe the persistent index.
 *
 * The repository's contract is **unchanged**
 * (it still returns `Flow<List<GalleryMedia>>`),
 * so the existing `GalleryViewModel` works
 * without modification. The new wiring:
 *   - On first `getMediaFiles()` collect: a
 *     one-shot `indexer.scan(ContentResolverMediaSource(context), nowMs)`
 *     populates the index.
 *   - Then: `dao.observeAll()` is filtered to
 *     IMAGE + VIDEO entries; each entity is
 *     mapped to a `GalleryMedia` for the UI.
 *
 * The repository is **process-scoped**
 * (`@Singleton`); the scan is **one-shot per
 * first collect** (subsequent collects just
 * observe the Flow).
 */
@Singleton
class GalleryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: MediaIndexDao,
    private val indexer: MediaIndexer,
) {
    /**
     * The "did we already trigger the first scan"
     * flag. The flag is **process-scoped**
     * (the repository is `@Singleton`); the
     * first `getMediaFiles()` collect triggers
     * the scan; subsequent collects skip the
     * scan and just observe the Flow.
     */
    @Volatile
    private var initialScanTriggered: Boolean = false

    /**
     * The coroutine scope used to trigger
     * the first scan asynchronously. The
     * scope is `SupervisorJob` + `Dispatchers.IO`
     * (the scan reads `MediaStore` + writes
     * to Room; both are I/O-bound).
     */
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO,
    )

    fun getMediaFiles(): Flow<List<GalleryMedia>> =
        dao.observeAll()
            .map { entities ->
                entities
                    .filter { entity ->
                        entity.mediaType == MediaType.IMAGE.name ||
                            entity.mediaType == MediaType.VIDEO.name
                    }
                    .map { it.toGalleryMedia() }
            }
            .onStart {
                // Trigger the first scan on the
                // first collect. The flag is
                // process-scoped; subsequent
                // collects skip this block.
                if (!initialScanTriggered) {
                    initialScanTriggered = true
                    scope.launch {
                        val source = ContentResolverMediaSource(
                            context = context,
                        )
                        indexer.scan(
                            source = source,
                            nowMs = System.currentTimeMillis(),
                        )
                    }
                }
            }
            .flowOn(Dispatchers.IO)

    fun deleteMedia(media: GalleryMedia): Boolean {
        return try {
            val contentUri = if (media.mimeType.startsWith("video")) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val uri = ContentUris.withAppendedId(contentUri, media.id)
            val rows = context.contentResolver.delete(uri, null, null)
            // The indexer will pick up the
            // deletion on the next scan (the
            // entity won't be in
            // ContentResolverMediaSource.discover()
            // anymore; the diff algorithm will
            // see the entity is missing + the
            // 24h grace period kicks in).
            rows > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

/**
 * Phase 94 — the `GalleryMedia` data class.
 * Unchanged from the previous implementation
 * (kept here for the file's self-containedness;
 * the constructor signature is identical to
 * the original).
 */
data class GalleryMedia(
    val id: Long,
    val name: String,
    val path: String,
    val mimeType: String,
    val dateModified: Long,
    val isFavorite: Boolean = false,
)

/**
 * Phase 94 — the mapping from the persistent
 * `MediaIndexEntity` to the UI-shaped
 * `GalleryMedia`. The mapping is the
 * **typed bridge** between the index
 * schema + the UI's data class.
 *
 * The mapping is **lossy** (some index
 * fields are not exposed to the UI):
 *   - `MediaIndexEntity.uri` → `GalleryMedia.path`
 *     (the UI's `path` field actually holds
 *     the `content://` URI; consumers that
 *     need a filesystem path can convert
 *     via `ContentResolver.openInputStream`).
 *   - `MediaIndexEntity.mediaId` → `GalleryMedia.id`
 *   - `MediaIndexEntity.displayName` → `GalleryMedia.name`
 *   - `MediaIndexEntity.mimeType` → `GalleryMedia.mimeType`
 *     (Phase 94 added the `mimeType` column
 *     to the index; previously the
 *     `GalleryRepository.deleteMedia` had
 *     to re-query `MediaStore` to know if
 *     the file was an image or a video).
 *   - `MediaIndexEntity.dateModifiedMs` →
 *     `GalleryMedia.dateModified`
 *   - `MediaIndexEntity.isFavorite` →
 *     `GalleryMedia.isFavorite` (the user-
 *     scoped favorite flag persists in the
 *     index; the UI overrides it with the
 *     in-memory `_favorites` set in the
 *     `GalleryViewModel`).
 */
private fun MediaIndexEntity.toGalleryMedia(): GalleryMedia =
    GalleryMedia(
        id = mediaId,
        name = displayName,
        path = uri,
        mimeType = mimeType,
        dateModified = dateModifiedMs,
        isFavorite = isFavorite,
    )
