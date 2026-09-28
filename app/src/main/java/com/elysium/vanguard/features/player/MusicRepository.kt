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
 * Phase 145 added these fields to the
 * persistent `MediaIndexEntity`. The
 * `ContentResolverMediaSource` now queries
 * `MediaStore.Audio.Media.ALBUM`,
 * `MediaStore.Audio.Media.ARTIST`, and
 * `MediaStore.Audio.Media.DURATION` for
 * audio items, and the `MediaIndexer`
 * persists them in the index. The
 * `MusicTrack.album`, `MusicTrack.artist`,
 * and `MusicTrack.duration` are now
 * populated from the index.
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
 * Phase 145 — the rich metadata (`album`,
 * `artist`, `duration`) is now populated
 * from the index columns added in the
 * MIGRATION_2_3 migration.
 */
private fun MediaIndexEntity.toMusicTrack(): MusicTrack =
    MusicTrack(
        id = mediaId,
        name = displayName,
        path = uri,
        mimeType = mimeType,
        album = album,
        artist = artist,
        duration = durationMs,
        dateModified = dateModifiedMs,
        isFavorite = isFavorite,
    )
