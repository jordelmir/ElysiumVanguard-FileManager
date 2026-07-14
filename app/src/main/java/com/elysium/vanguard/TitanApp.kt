package com.elysium.vanguard

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
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

    override fun onCreate() {
        super.onCreate()
        // ProcessLifecycleOwner dispatches ON_START when the first
        // activity becomes visible and ON_STOP when the last one is
        // hidden. Registering the binder once at process boot is
        // enough — the binder is itself idempotent.
        ProcessLifecycleOwner.get().lifecycle.addObserver(guestDnsLifecycleBinder)
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
