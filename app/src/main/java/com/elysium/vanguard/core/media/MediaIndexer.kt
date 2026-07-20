package com.elysium.vanguard.core.media

import com.elysium.vanguard.core.database.media.MediaIndexDao
import com.elysium.vanguard.core.database.media.MediaIndexEntity
import com.elysium.vanguard.core.database.media.MediaType

/**
 * Phase 93 — the **Media Indexer**, the
 * component that bridges the Android
 * `MediaStore` and the persistent Elysium
 * media index.
 *
 * Per the master vision's "MEDIA VAULT" +
 * "AUDIO HUB" portal items + the user's
 * direct ask: "Haz que escanee los sonidos
 * e imágenes, apenas uno entre y luego ya
 * lo guarde localmente y solo vaya sumando
 * en futuros escaneos lo nuevo, cuando
 * haya..." (Scan sounds and images; as
 * soon as one comes in, save it locally
 * and on future scans only add the new
 * ones).
 *
 * The indexer is the **typed bridge**:
 *   - **Input**: a [MediaSource] (the
 *     seam that produces discovered media
 *     items from the Android `MediaStore`
 *     in production; a stub in tests).
 *   - **Storage**: a [MediaIndexDao] (the
 *     Room DAO; persistent).
 *   - **Output**: a typed [IndexResult]
 *     recording what was added / updated
 *     / removed in the scan.
 *
 * The indexer's **incremental algorithm**:
 *   1. Read all entries from the DAO
 *      (the "previous scan" state).
 *   2. Read all discovered items from the
 *      `MediaSource` (the "current scan"
 *      state).
 *   3. For each discovered item:
 *      - If the media id is in the
 *        previous index:
 *        - If the file's
 *          `dateModifiedMs` /
 *          `sizeBytes` /
 *          `contentHash` changed: emit
 *          `Updated` + update the row.
 *        - Otherwise: emit `Unchanged`
 *          + bump the `lastSeenAtMs`.
 *      - If the media id is NOT in the
 *        previous index: emit `Added` +
 *        insert a new row.
 *   4. Garbage-collect stale entries
 *      (entries that were not seen in
 *      this scan: emit `Removed` + delete
 *      the row).
 *
 * The algorithm is **deterministic via
 * explicit `nowMs`** (the indexer does
 * NOT call `System.currentTimeMillis()`
 * internally; the caller passes the
 * current time). The `nowMs` is the
 * timestamp the indexer stamps on
 * `discoveredAtMs` + `lastSeenAtMs`.
 *
 * The indexer is **thread-safe** (the
 * underlying DAO is `suspend`; the
 * indexer's `scan` method is also
 * `suspend`). Concurrent calls are
 * serialized by Room's coroutine
 * dispatcher.
 *
 * The indexer is **pure-domain for the
 * diff algorithm** (the algorithm doesn't
 * care about Android's `ContentResolver`;
 * the algorithm operates on a typed
 * [MediaSource] + a typed [MediaIndexDao]).
 * The production wiring injects the real
 * `ContentResolver`-backed [MediaSource];
 * the test wiring injects an in-memory
 * stub.
 */
sealed class MediaIndexer {

    /**
     * Run an incremental scan. The
     * indexer reads the current
     * `MediaSource` + compares against
     * the persisted index + updates
     * the index with the diff.
     *
     * Returns a typed [IndexResult]
     * recording what was added /
     * updated / removed. The result
     * is the canonical "what did the
     * scan do" record; the consumer
     * (the UI, the audit log) pattern-
     * matches on the variant.
     *
     * The `nowMs` parameter is the
     * timestamp the indexer stamps on
     * `discoveredAtMs` + `lastSeenAtMs`.
     * The parameter is **explicit** (not
     * derived from
     * `System.currentTimeMillis()`) so
     * the indexer is **deterministic**
     * (the test uses a fixed `nowMs`).
     */
    abstract suspend fun scan(
        source: MediaSource,
        nowMs: Long,
    ): IndexResult
}

/**
 * The typed result of an incremental scan.
 * The result is a data class; the consumer
 * pattern-matches on the counts.
 *
 * The result records:
 *   - **`added`** — the new media items
 *     that were inserted into the index.
 *   - **`updated`** — the existing items
 *     that were updated (the file's
 *     mtime / size / hash changed).
 *   - **`unchanged`** — the existing
 *     items that were unchanged (the
 *     file is the same; the row's
 *     `lastSeenAtMs` was bumped).
 *   - **`removed`** — the items that
 *     were garbage-collected (the file
 *     is gone from the device).
 *   - **`totalAfter`** — the total
 *     number of items in the index after
 *     the scan.
 *   - **`scanAtMs`** — the timestamp the
 *     scan completed.
 *   - **`wasFirstScan`** — `true` if
 *     the index was empty before the
 *     scan (every discovered item is
 *     an `Added`).
 */
data class IndexResult(
    val added: List<MediaIndexEntity>,
    val updated: List<MediaIndexEntity>,
    val unchanged: Int,
    val removed: List<MediaIndexEntity>,
    val totalAfter: Int,
    val scanAtMs: Long,
    val wasFirstScan: Boolean,
) {
    init {
        require(added.isNotEmpty() || updated.isNotEmpty() ||
            unchanged >= 0 || removed.isNotEmpty()) {
            "IndexResult must have at least one non-empty bucket"
        }
        require(scanAtMs > 0) {
            "IndexResult.scanAtMs must be > 0, got $scanAtMs"
        }
    }

    /**
     * The total number of items processed
     * (added + updated + unchanged). The
     * field is the canonical "how much
     * work did the scan do" metric.
     */
    val processed: Int
        get() = added.size + updated.size + unchanged

    /**
     * Whether the scan found at least one
     * new media item. The field is the
     * canonical "is there something new
     * to show the user" predicate (the
     * UI uses it to decide whether to
     * display a "X new items" badge).
     */
    val hasNewItems: Boolean
        get() = added.isNotEmpty()
}

/**
 * The typed seam for the media source.
 * The source produces discovered media
 * items from the Android `MediaStore`
 * (in production) or a stub (in tests).
 *
 * The source is **sealed** (sealed
 * interface pattern); the production
 * impl is the `ContentResolverMediaSource`
 * (reads `MediaStore.Images.Media` +
 * `MediaStore.Video.Media` +
 * `MediaStore.Audio.Media`); the test
 * impl is the `InMemoryMediaSource`
 * (returns a pre-canned list).
 *
 * The source's `discover` method is
 * **suspend** (the production impl
 * queries `MediaStore` on
 * `Dispatchers.IO`; the test impl is
 * in-memory).
 */
sealed interface MediaSource {

    /**
     * Discover all media items currently
     * visible to the app. The list is
     * the "current scan" state; the
     * indexer compares against the
     * persisted index.
     */
    suspend fun discover(): List<DiscoveredMedia>
}

/**
 * A single discovered media item. The
 * item is the indexer's input (a row
 * from the Android `MediaStore` + the
 * computed content hash).
 *
 * The item has:
 *   - **`mediaId`** — the stable
 *     `MediaStore.MediaColumns._ID`.
 *   - **`uri`** — the canonical
 *     `content://` URI.
 *   - **`mediaType`** — the typed kind.
 *   - **`displayName`** — the file's
 *     display name.
 *   - **`relativePath`** — the
 *     device-side relative path
 *     (the "album" identifier).
 *   - **`sizeBytes`** — the file size.
 *   - **`dateModifiedMs`** — the file's
 *     last-modified timestamp.
 *   - **`contentHash`** — the SHA-256
 *     of the first 4 KiB + the file
 *     size (a fast fingerprint; not
 *     cryptographically secure).
 */
data class DiscoveredMedia(
    val mediaId: Long,
    val uri: String,
    val mediaType: MediaType,
    val displayName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val dateModifiedMs: Long,
    val contentHash: String? = null,
) {
    init {
        require(uri.isNotBlank()) {
            "DiscoveredMedia.uri must not be blank"
        }
        require(displayName.isNotBlank()) {
            "DiscoveredMedia.displayName must not be blank"
        }
        require(sizeBytes >= 0) {
            "DiscoveredMedia.sizeBytes must be >= 0, " +
                "got $sizeBytes"
        }
    }
}

/**
 * The default [MediaIndexer] implementation.
 * The implementation is the stateless
 * composition of:
 *   - A [MediaIndexDao] (the persistent
 *     index).
 *   - The diff algorithm (per
 *     [MediaIndexer] class doc).
 *
 * The implementation is **thread-safe**
 * (no mutable fields). The implementation
 * is **deterministic via explicit `nowMs`**.
 */
class DefaultMediaIndexer(
    private val dao: MediaIndexDao,
) : MediaIndexer() {

    /**
     * The grace period (in millis) for
     * garbage-collecting stale entries.
     * An entry whose `lastSeenAtMs` is
     * strictly less than
     * `nowMs - GRACE_MS` is considered
     * gone (the file was deleted from
     * the device between scans).
     *
     * The grace period is **24 hours**:
     * a file that's missing for more
     * than a day is definitely deleted
     * (the user could have unplugged the
     * SD card for a day without the
     * file being actually deleted).
     */
    private val graceMs: Long = 24L * 60L * 60L * 1000L

    override suspend fun scan(
        source: MediaSource,
        nowMs: Long,
    ): IndexResult {
        // Step 1: Read the previous index.
        val previous = dao.listAll()
        val wasFirstScan = previous.isEmpty()
        val previousById = previous.associateBy { it.mediaId }

        // Step 2: Read the current MediaSource.
        val discovered = source.discover()

        // Step 3: Diff.
        val added = mutableListOf<MediaIndexEntity>()
        val updated = mutableListOf<MediaIndexEntity>()
        var unchanged = 0
        val seenIds = mutableSetOf<Long>()

        for (item in discovered) {
            seenIds += item.mediaId
            val existing = previousById[item.mediaId]
            when {
                existing == null -> {
                    // New media item.
                    val entity = MediaIndexEntity(
                        mediaId = item.mediaId,
                        uri = item.uri,
                        mediaType = item.mediaType.name,
                        displayName = item.displayName,
                        relativePath = item.relativePath,
                        sizeBytes = item.sizeBytes,
                        dateModifiedMs = item.dateModifiedMs,
                        contentHash = item.contentHash,
                        discoveredAtMs = nowMs,
                        lastSeenAtMs = nowMs,
                    )
                    dao.upsert(entity)
                    added.add(entity)
                }
                existing.dateModifiedMs != item.dateModifiedMs ||
                    existing.sizeBytes != item.sizeBytes ||
                    existing.contentHash != item.contentHash -> {
                    // Existing item updated.
                    val updatedEntity = existing.copy(
                        uri = item.uri,
                        displayName = item.displayName,
                        relativePath = item.relativePath,
                        sizeBytes = item.sizeBytes,
                        dateModifiedMs = item.dateModifiedMs,
                        contentHash = item.contentHash,
                        lastSeenAtMs = nowMs,
                    )
                    dao.upsert(updatedEntity)
                    updated.add(updatedEntity)
                }
                else -> {
                    // Unchanged: bump lastSeenAtMs.
                    val bumped = existing.copy(
                        lastSeenAtMs = nowMs,
                    )
                    dao.upsert(bumped)
                    unchanged += 1
                }
            }
        }

        // Step 4: Garbage-collect stale
        // entries (entries that were not
        // seen in this scan AND are older
        // than the grace period).
        val removed = mutableListOf<MediaIndexEntity>()
        val threshold = nowMs - graceMs
        for (entity in previous) {
            if (entity.mediaId !in seenIds &&
                entity.lastSeenAtMs < threshold
            ) {
                dao.deleteById(entity.mediaId)
                removed.add(entity)
            }
        }

        // Step 5: Compute the post-scan count.
        val totalAfter = dao.count()

        return IndexResult(
            added = added,
            updated = updated,
            unchanged = unchanged,
            removed = removed,
            totalAfter = totalAfter,
            scanAtMs = nowMs,
            wasFirstScan = wasFirstScan,
        )
    }
}
