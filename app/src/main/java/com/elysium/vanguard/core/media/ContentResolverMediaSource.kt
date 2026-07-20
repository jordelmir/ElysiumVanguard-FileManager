package com.elysium.vanguard.core.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.elysium.vanguard.core.database.media.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Phase 93 — the **Content Resolver Media
 * Source**, the production [MediaSource]
 * implementation that reads from the
 * Android `MediaStore`.
 *
 * The source is the **seam** between the
 * pure-domain [MediaIndexer] and the
 * Android `ContentResolver`. The source
 * is the only class in the indexer
 * pipeline that imports `android.*`
 * classes; everything else is
 * pure-domain (testable on the JVM
 * without an emulator).
 *
 * The source queries three `MediaStore`
 * URIs:
 *   - `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`
 *     — for the MEDIA VAULT's images.
 *   - `MediaStore.Video.Media.EXTERNAL_CONTENT_URI`
 *     — for the MEDIA VAULT's videos.
 *   - `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI`
 *     — for the AUDIO HUB's music.
 *
 * For each row, the source computes a
 * **fast content fingerprint** (SHA-256
 * of the first 4 KiB + the file size).
 * The fingerprint is the canonical
 * "did the file's content change?"
 * check (a file may be replaced in
 * place with the same mtime but
 * different content; the fingerprint
 * detects the change).
 *
 * The source is **thread-safe** (the
 * `discover` method is `suspend` +
 * uses `withContext(Dispatchers.IO)`
 * for the I/O work).
 */
class ContentResolverMediaSource(
    private val context: Context,
) : MediaSource {

    override suspend fun discover(): List<DiscoveredMedia> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<DiscoveredMedia>()
            result += queryUri(
                uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                mediaType = MediaType.IMAGE,
            )
            result += queryUri(
                uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                mediaType = MediaType.VIDEO,
            )
            result += queryUri(
                uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                mediaType = MediaType.AUDIO,
            )
            result
        }

    /**
     * Query a single `MediaStore` URI. The
     * function returns a list of
     * [DiscoveredMedia] for the URI.
     *
     * The query is **bounded** (no images
     * or videos are loaded into memory;
     * only the metadata columns are read
     * + the first 4 KiB of the file for
     * the content hash).
     */
    private fun queryUri(
        uri: Uri,
        mediaType: MediaType,
    ): List<DiscoveredMedia> {
        val result = mutableListOf<DiscoveredMedia>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        context.contentResolver.query(uri, projection, null, null, sortOrder)
            ?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.MediaColumns._ID,
                )
                val nameColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                )
                val sizeColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.MediaColumns.SIZE,
                )
                val dateColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.MediaColumns.DATE_MODIFIED,
                )
                val pathColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                )
                val mimeColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.MediaColumns.MIME_TYPE,
                )
                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(idColumn)
                    val itemUri = ContentUris.withAppendedId(
                        uri,
                        mediaId,
                    )
                    val size = cursor.getLong(sizeColumn)
                    val contentHash = computeContentHash(itemUri, size)
                    result.add(
                        DiscoveredMedia(
                            mediaId = mediaId,
                            uri = itemUri.toString(),
                            mediaType = mediaType,
                            displayName = cursor.getString(nameColumn),
                            relativePath = cursor.getString(pathColumn) ?: "",
                            sizeBytes = size,
                            dateModifiedMs = cursor.getLong(dateColumn) * 1000L,
                            mimeType = cursor.getString(mimeColumn) ?: "",
                            contentHash = contentHash,
                        ),
                    )
                }
            }
        return result
    }

    /**
     * Compute the fast content fingerprint
     * (SHA-256 of the first 4 KiB + the
     * file size). The fingerprint is the
     * canonical "did the file's content
     * change?" check.
     *
     * The function returns `null` when
     * the content URI cannot be opened
     * (the file is missing, the
     * permission was revoked, etc.). A
     * `null` hash is a degraded signal
     * (the indexer falls back to
     * mtime + size for the change
     * detection).
     */
    private fun computeContentHash(
        uri: Uri,
        sizeBytes: Long,
    ): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(4096)
            val read = stream.read(buffer)
            if (read > 0) {
                digest.update(buffer, 0, read)
            }
        }
        digest.update(sizeBytes.toString().toByteArray(Charsets.UTF_8))
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }
}

/**
 * The in-memory [MediaSource] for testing.
 * The source is the **test seam** for the
 * [MediaIndexer] (the test injects an
 * in-memory source instead of the real
 * `ContentResolverMediaSource`).
 *
 * The source is **mutable** (the test can
 * add / remove items between scans). The
 * source is **thread-safe** (the underlying
 * list is a `CopyOnWriteArrayList`).
 */
class InMemoryMediaSource : MediaSource {

    private val items: MutableList<DiscoveredMedia> =
        java.util.concurrent.CopyOnWriteArrayList()

    override suspend fun discover(): List<DiscoveredMedia> =
        items.toList()

    /**
     * Add an item to the source. The
     * test uses this to simulate a
     * new media item being added to
     * the device.
     */
    fun add(item: DiscoveredMedia) {
        items.add(item)
    }

    /**
     * Remove an item from the source by
     * media id. The test uses this to
     * simulate a media item being
     * deleted from the device.
     */
    fun removeById(mediaId: Long) {
        items.removeAll { it.mediaId == mediaId }
    }

    /**
     * Update an item in the source. The
     * test uses this to simulate a file
     * being modified in place (the
     * `dateModifiedMs` + the `contentHash`
     * change).
     */
    fun update(item: DiscoveredMedia) {
        val index = items.indexOfFirst { it.mediaId == item.mediaId }
        if (index >= 0) {
            items[index] = item
        }
    }

    /**
     * Clear all items. The test uses
     * this to reset the source between
     * test scenarios.
     */
    fun clear() {
        items.clear()
    }

    /**
     * The current size. The test uses
     * this to assert the source has the
     * expected number of items.
     */
    val size: Int
        get() = items.size
}
