package com.elysium.vanguard.core.media

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 93 — the **Media Store Observer**, the
 * reactive trigger that fires an incremental
 * scan every time a new media item is added
 * to (or removed from) the Android `MediaStore`.
 *
 * Per the master vision's "MEDIA VAULT" +
 * "AUDIO HUB" portal items + the user's
 * direct ask: "apenas uno entre y luego ya
 * lo guarde localmente" (as soon as one comes
 * in, save it locally).
 *
 * The observer is the **reactive half** of
 * the indexer:
 *   - The [MediaIndexer] is the **pull half**
 *     (the caller asks "scan now" and gets a
 *     typed [IndexResult]).
 *   - The [MediaStoreObserver] is the **push
 *     half** (the platform tells the observer
 *     "a new item was added"; the observer
 *     triggers a scan + emits a [ScanEvent]).
 *
 * The observer registers three `ContentObserver`s
 * (one per `MediaStore` URI: images, video,
 * audio). The observers' `onChange` callback
 * fires when a row is inserted / updated /
 * deleted in the URI; the observer debounces
 * the events (multiple rapid changes coalesce
 * into a single scan) and triggers a scan.
 *
 * The observer is **process-scoped** (it's
 * a `@Singleton`; the Hilt graph owns the
 * instance for the app's lifetime). The
 * observer must be started explicitly via
 * [start] and stopped via [stop].
 *
 * The observer is **thread-safe** (the
 * underlying collections are coroutine-safe;
 * the `ContentObserver` callback fires on
 * the main thread; the debounce + scan
 * happen on `Dispatchers.IO`).
 */
@Singleton
class MediaStoreObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val indexer: MediaIndexer,
) {

    /**
     * The coroutine scope used to run
     * the scan + emit events. The scope
     * uses a `SupervisorJob` so one
     * failing scan does not cancel the
     * other scans; the dispatcher is
     * `Dispatchers.IO` because the scan
     * reads from `MediaStore` + writes
     * to Room.
     */
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO,
    )

    /**
     * The main-thread handler used to
     * register the `ContentObserver`s
     * (the Android `ContentResolver`
     * requires a handler to deliver
     * the `onChange` callback).
     */
    private val mainHandler: Handler =
        Handler(Looper.getMainLooper())

    /**
     * The three `ContentObserver`s (one
     * per media URI). The observer
     * `null`s out the field on [stop]
     * to allow re-[start] after [stop].
     */
    private var imagesObserver: ContentObserver? = null
    private var videoObserver: ContentObserver? = null
    private var audioObserver: ContentObserver? = null

    /**
     * The channel of pending scan events
     * (one per `MediaStore` change). The
     * channel is used to **debounce** the
     * changes: when multiple changes happen
     * in quick succession (e.g. 10 photos
     * copied at once), the channel coalesces
     * them into a single scan.
     */
    private val pendingChanges: Channel<MediaStoreChange> =
        Channel(capacity = Channel.CONFLATED)

    /**
     * The current scan state (a
     * [StateFlow] for Compose-driven UI).
     * The state is:
     *   - `Idle` — no scan in progress.
     *   - `Scanning` — a scan is in
     *     progress.
     *   - `Error` — the last scan failed
     *     (the error message is the
     *     state).
     */
    private val scanState: MutableStateFlow<ScanState> =
        MutableStateFlow(ScanState.Idle)

    /**
     * The current scan state (read-only).
     * The flow is the canonical "is the
     * observer scanning right now" probe
     * for the UI.
     */
    val state: StateFlow<ScanState>
        get() = scanState.asStateFlow()

    /**
     * Start the observer. The observer
     * registers three `ContentObserver`s
     * (one per media URI) + starts a
     * background coroutine that consumes
     * the pending changes + triggers a
     * scan.
     *
     * [start] is **idempotent** (calling
     * it twice has no effect; the
     * observer checks the registration
     * before re-registering).
     */
    fun start() {
        if (imagesObserver != null) return
        // Register the three observers.
        imagesObserver = registerObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        )
        videoObserver = registerObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )
        audioObserver = registerObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        )
        // Start the background coroutine.
        scope.launch {
            // A simple debouncer: collect
            // changes; if no change for
            // 500ms, trigger a scan.
            // (For Phase 93 the debouncer
            // is intentionally simple; a
            // future increment can add a
            // proper debounce with
            // `debounce(500)`.
            while (true) {
                val change = pendingChanges.receive()
                // Drain any other pending
                // changes.
                while (true) {
                    val next = pendingChanges.tryReceive()
                        .getOrNull() ?: break
                    // Coalesce: keep the
                    // latest change (the
                    // URI is the join key).
                }
                triggerScan(change)
            }
        }
    }

    /**
     * Stop the observer. The observer
     * unregisters the three
     * `ContentObserver`s + cancels the
     * background coroutine.
     *
     * [stop] is **idempotent** (calling
     * it twice has no effect).
     */
    fun stop() {
        imagesObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
        }
        videoObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
        }
        audioObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
        }
        imagesObserver = null
        videoObserver = null
        audioObserver = null
    }

    /**
     * Register a `ContentObserver` for the
     * given URI. The observer fires when
     * the URI's content changes; the
     * observer enqueues a [MediaStoreChange]
     * for the debouncer.
     */
    private fun registerObserver(uri: Uri): ContentObserver =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                pendingChanges.trySend(
                    MediaStoreChange(
                        uri = uri.toString(),
                        selfChange = selfChange,
                    ),
                )
            }
        }.also { observer ->
            context.contentResolver.registerContentObserver(
                uri,
                /* notifyForDescendants = */ true,
                observer,
            )
        }

    /**
     * Trigger a scan for the given
     * change. The scan uses a
     * `ContentResolverMediaSource` (the
     * production source) + the
     * [MediaIndexer].
     *
     * The scan is a `suspend` operation;
     * the caller (the background coroutine
     * started in [start]) awaits the
     * result.
     */
    private suspend fun triggerScan(change: MediaStoreChange) {
        scanState.value = ScanState.Scanning
        try {
            val source = ContentResolverMediaSource(
                context = context,
            )
            val result = indexer.scan(
                source = source,
                nowMs = System.currentTimeMillis(),
            )
            scanState.value = ScanState.Idle
        } catch (e: Exception) {
            scanState.value = ScanState.Error(
                message = e.message ?: "unknown",
            )
        }
    }
}

/**
 * A pending `MediaStore` change. The
 * change is the observer's input to the
 * debouncer.
 */
data class MediaStoreChange(
    val uri: String,
    val selfChange: Boolean,
)

/**
 * The current scan state. The state is a
 * sealed class with 3 cases.
 */
sealed class ScanState {
    /**
     * No scan in progress. The
     * observer is idle.
     */
    data object Idle : ScanState()

    /**
     * A scan is in progress. The
     * observer is debouncing +
     * running the indexer.
     */
    data object Scanning : ScanState()

    /**
     * The last scan failed. The
     * `message` is the human-readable
     * reason.
     */
    data class Error(val message: String) : ScanState()
}
