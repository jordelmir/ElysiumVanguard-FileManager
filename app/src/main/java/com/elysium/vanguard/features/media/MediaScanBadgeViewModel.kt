package com.elysium.vanguard.features.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.database.media.MediaType
import com.elysium.vanguard.core.media.MediaStoreObserver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * PHASE 118 — the **media scan badge ViewModel**, the
 * UI-side bridge from the [MediaStoreObserver] to the
 * "X new items" badge on the MEDIA VAULT + AUDIO HUB
 * dashboard tiles.
 *
 * The ViewModel exposes two `StateFlow<Int>` values:
 *   - [newMediaItemCount] — the number of new IMAGE
 *     or VIDEO items the last scan found (drives the
 *     MEDIA VAULT badge).
 *   - [newAudioItemCount] — the number of new AUDIO
 *     items the last scan found (drives the AUDIO HUB
 *     badge).
 *
 * The ViewModel is **read-only**; the badge is a
 * one-way projection of the observer's latest
 * `IndexResult.added` list. The ViewModel does not
 * trigger any scans itself — the [MediaStoreObserver]
 * does that, in the background, registered at app
 * boot in `com.elysium.vanguard.TitanApp.onCreate`.
 *
 * The ViewModel is **stateless** (default per-Activity
 * scope). The observer is the source of truth; the
 * ViewModel is a thin projection.
 *
 * Pattern note: the [stateIn] operator with
 * [SharingStarted.WhileSubscribed] is the right
 * shape for a Compose-driven badge (the badge
 * subscribes when the dashboard is visible; the
 * upstream `lastResult` flow is paused after a
 * short idle to avoid holding the observer's
 * hot flow open when no UI is interested).
 */
@HiltViewModel
class MediaScanBadgeViewModel @Inject constructor(
    private val mediaStoreObserver: MediaStoreObserver,
) : ViewModel() {

    /**
     * The number of new IMAGE or VIDEO items the
     * last scan found. The flow is `0` before
     * the first scan, `> 0` when the last scan
     * discovered new visual content.
     *
     * The flow is derived from the observer's
     * `lastResult.added` list (the canonical
     * "what did the scan add" record). The
     * filter to IMAGE + VIDEO excludes audio
     * items (the AUDIO HUB gets those).
     */
    val newMediaItemCount: StateFlow<Int> = mediaStoreObserver.lastResult
        .map { result ->
            result
                ?.added
                ?.count { it.mediaType == MediaType.IMAGE.name ||
                    it.mediaType == MediaType.VIDEO.name }
                ?: 0
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0,
        )

    /**
     * The number of new AUDIO items the last
     * scan found. The flow mirrors
     * [newMediaItemCount] but filters to AUDIO
     * only.
     */
    val newAudioItemCount: StateFlow<Int> = mediaStoreObserver.lastResult
        .map { result ->
            result
                ?.added
                ?.count { it.mediaType == MediaType.AUDIO.name }
                ?: 0
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0,
        )
}
