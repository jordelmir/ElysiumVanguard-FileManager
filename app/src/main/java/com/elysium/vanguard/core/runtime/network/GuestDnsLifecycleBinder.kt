package com.elysium.vanguard.core.runtime.network

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wires [GuestDnsSessionTracker] to a [LifecycleOwner] (typically
 * [androidx.lifecycle.ProcessLifecycleOwner]).
 *
 * Master order §10.1: the PRoot guest's resolver must follow Android's
 * active network. The tracker is the single subscriber to the
 * [GuestDnsObserver] flow; if it is not started, no refresh fires.
 *
 * Lifecycle:
 *
 *   - `ON_START` → `tracker.start()` — begin collecting network changes.
 *     The tracker runs an explicit initial sync before subscribing to
 *     the flow, so the moment the app is foreground the active rootfses
 *     are guaranteed to have the latest resolver.
 *   - `ON_STOP` → `tracker.stop()` — cancel the subscription. We do
 *     this to release the `ConnectivityManager.NetworkCallback` while
 *     the app is in the background (battery and binder hygiene). The
 *     registry's per-rootfs closures stay registered; on the next
 *     `ON_START` the tracker re-syncs.
 *
 * The binder is idempotent: the tracker's own `start()` is idempotent,
 * and `stop()` cancels at most one job. So observing the same
 * Lifecycle twice (e.g. from two processes in tests) is safe.
 *
 * The binder is **stateless** beyond the [tracker] reference. It
 * carries no event queue, no coroutine scope. The `DefaultLifecycleObserver`
 * callbacks are invoked on the main thread; the tracker's work runs on
 * [kotlinx.coroutines.Dispatchers.Default] internally, so this method
 * does not block.
 */
@Singleton
class GuestDnsLifecycleBinder @Inject constructor(
    private val tracker: GuestDnsSessionTracker
) : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) {
        tracker.start()
    }

    override fun onStop(owner: LifecycleOwner) {
        tracker.stop()
    }
}
