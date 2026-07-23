package com.elysium.vanguard.core.media

import com.elysium.vanguard.core.database.media.MediaIndexDao
import com.elysium.vanguard.core.database.media.MediaIndexEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.CopyOnWriteArrayList

/**
 * PHASE 118 — **shared media test fixtures**.
 *
 * The `FakeMediaIndexDao` + `StubMediaSourceProvider`
 * classes are shared by all tests in the
 * `com.elysium.vanguard.core.media` package
 * (and any sub-package). The classes used to be
 * declared `private` in each test file (3
 * duplicates across the project), which broke
 * the moment two tests in the same package tried
 * to share the same fixtures (Kotlin top-level
 * declarations with the same name in the same
 * package collide even when `private`).
 *
 * The shared fixtures are `internal` (module-
 * visible) so any test in the same module can
 * import them. The fixtures are still
 * hand-rolled in-memory implementations (no
 * Room, no ContentResolver) — the production
 * path uses the real Room + ContentResolver
 * wiring (covered by the instrumented tests).
 *
 * The `InMemoryMediaSource` is already shared
 * (it lives in the production source under
 * `com.elysium.vanguard.core.media`); the test
 * fixtures here are the new home for the DAO
 * fake + the provider stub.
 */
internal class FakeMediaIndexDao : MediaIndexDao {

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

/**
 * The test stub for the [MediaSourceProvider]. The
 * stub returns a pre-canned [InMemoryMediaSource]
 * on every `invoke()` (the production
 * [MediaSourceProvider] returns a fresh
 * `ContentResolverMediaSource`; the test doesn't
 * need the Android `ContentResolver`).
 *
 * The `onInvoke` callback is invoked on every
 * `invoke()` (the test uses it to count the
 * invocations).
 */
internal class StubMediaSourceProvider(
    context: android.content.Context,
    private val source: InMemoryMediaSource,
    private val onInvoke: () -> Unit = {},
) : MediaSourceProvider(context) {
    override fun invoke(): MediaSource {
        onInvoke()
        return source
    }
}
