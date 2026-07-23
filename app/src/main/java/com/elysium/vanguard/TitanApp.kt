package com.elysium.vanguard

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.elysium.vanguard.core.media.MediaStoreObserver
import com.elysium.vanguard.core.runtime.network.GuestDnsLifecycleBinder
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TitanApp : Application(), ImageLoaderFactory {

    /**
     * PHASE 11.3 — process-wide binder that subscribes the DNS tracker
     * to the app lifecycle. The tracker only runs while the app is
     * visible (foreground), avoiding a permanent
     * `ConnectivityManager.NetworkCallback` while the user is not
     * looking.
     */
    @Inject
    lateinit var guestDnsLifecycleBinder: GuestDnsLifecycleBinder

    /**
     * PHASE 118 — the media index observer. The observer is
     * auto-started at app boot so the MEDIA VAULT + AUDIO HUB
     * tiles stay in sync with `MediaStore` in real time (the
     * observer registers `ContentObserver`s on the three
     * `MediaStore` URIs; every new photo / video / audio the
     * user copies to the device triggers a debounced
     * incremental scan).
     *
     * The observer is **process-scoped** (it's a `@Singleton` via
     * its `@Inject constructor`); the explicit `start()` is
     * idempotent. The Hilt graph constructs it once at process
     * boot; the call here is what wires the reactive trigger.
     */
    @Inject
    lateinit var mediaStoreObserver: MediaStoreObserver

    override fun onCreate() {
        super.onCreate()
        // ProcessLifecycleOwner dispatches ON_START when the first
        // activity becomes visible and ON_STOP when the last one is
        // hidden. Registering the binder once at process boot is
        // enough — the binder is itself idempotent.
        ProcessLifecycleOwner.get().lifecycle.addObserver(guestDnsLifecycleBinder)
        // PHASE 118 — start the media index observer so the
        // incremental scanner fires on every MediaStore change
        // (the observer debounces the events; multiple rapid
        // changes coalesce into a single scan).
        mediaStoreObserver.start()
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
}
