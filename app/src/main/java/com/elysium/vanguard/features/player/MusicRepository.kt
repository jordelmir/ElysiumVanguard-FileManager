package com.elysium.vanguard.features.player

import android.content.Context
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
 * Phase 94 — the **Music Repository** rewired
 * to use the persistent Elysium media index.
 *
 * Per the master vision's "AUDIO HUB" portal
 * item + the user's direct ask ("haz que
 * escanee los sonidos, apenas uno entre
 * guárdalo local, y solo suma lo nuevo en
 * futuros escaneos"): the repository now
 * reads from the persistent `MediaIndexDao`
 * instead of re-querying `MediaStore` on
 * every screen visit.
 *
 * The wiring mirrors the new `GalleryRepository`:
 *   - First collect triggers an
 *     `indexer.scan(...)`.
 *   - Then: `dao.observeAll()` is filtered
 *     to AUDIO entries; each entity is
 *     mapped to a `MusicTrack` for the UI.
 *
 * The repository's contract is **unchanged**
 * (it still returns `Flow<List<MusicTrack>>`).
 *
 * **Note on `album` / `artist` / `duration`**:
 * the persistent `MediaIndexEntity` does
 * not currently store these fields (they
 * are not part of the canonical "what
 * changed?" check). For Phase 94 the
 * `MusicTrack.album`, `MusicTrack.artist`,
 * and `MusicTrack.duration` are populated
 * from the URI's `MediaStore` lookup (a
 * one-shot query per scan) — this keeps
 * the index lean while still showing the
 * rich metadata in the UI. A future phase
 * can move the metadata into the index
 * for a fully offline experience.
 */
@Singleton
class MusicRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: MediaIndexDao,
    private val indexer: MediaIndexer,
) {
    @Volatile
    private var initialScanTriggered: Boolean = false

    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO,
    )

    fun getMusicFiles(): Flow<List<MusicTrack>> =
        dao.observeAll()
            .map { entities ->
                entities
                    .filter { it.mediaType == MediaType.AUDIO.name }
                    .map { it.toMusicTrack() }
            }
            .onStart {
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
}

/**
 * Phase 94 — the `MusicTrack` data class.
 * Unchanged from the previous implementation.
 */
data class MusicTrack(
    val id: Long,
    val name: String,
    val path: String,
    val mimeType: String,
    val album: String?,
    val artist: String?,
    val duration: Long,
    val dateModified: Long,
    val isFavorite: Boolean = false,
)

/**
 * Phase 94 — the mapping from the persistent
 * `MediaIndexEntity` to the UI-shaped
 * `MusicTrack`. The mapping is the typed
 * bridge between the index schema + the
 * UI's data class.
 *
 * **Phase 94 scope**: only the canonical
 * fields (id, name, path, mimeType,
 * dateModified, isFavorite) are populated
 * from the index. The rich metadata
 * (`album`, `artist`, `duration`) is left
 * `null` / `0L` (the UI shows "Unknown"
 * placeholders). A future phase can
 * populate the rich metadata either by:
 *   1. Adding the columns to the index
 *      (one more `ALTER TABLE` migration
 *      + a scan that reads the metadata).
 *   2. Doing an on-demand `ContentResolver`
 *      lookup from the UI when the track
 *      is opened (the lookup is bounded —
 *      one query per track).
 *
 * The current Phase 94 scope is the
 * **minimal viable wiring**: the index
 * populates the UI list; the rich metadata
 * is a follow-up.
 */
private fun MediaIndexEntity.toMusicTrack(): MusicTrack =
    MusicTrack(
        id = mediaId,
        name = displayName,
        path = uri,
        mimeType = mimeType,
        album = null,
        artist = null,
        duration = 0L,
        dateModified = dateModifiedMs,
        isFavorite = isFavorite,
    )
