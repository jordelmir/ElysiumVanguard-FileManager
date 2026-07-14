package com.elysium.vanguard.core.runtime.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Test-friendly and fallback implementation of [GuestDnsObserver].
 *
 * The snapshot comes from a caller-supplied lambda, and the change signal
 * comes from either [signalChange] (programmatic) or [refresh]. Tests use
 * this to simulate Wi-Fi → data flips without a real [android.net.ConnectivityManager].
 *
 * In production this is only used when the device cannot install the
 * default [AndroidGuestDnsObserver] (instrumented test, stripped build,
 * no connectivity service). The class is `open` so tests can subclass to
 * inject a custom snapshot.
 *
 * Contract mirrors the production [AndroidGuestDnsConfigProvider]:
 *
 *   - [current] always returns the *freshest* snapshot (no caching).
 *     This is what the launcher calls when it builds a proot command;
 *     the caller is responsible for cache invalidation, not us.
 *   - [observe] exposes a hot flow that replays the last published
 *     value to new subscribers and re-emits on every [signalChange]
 *     or [refresh] call.
 *
 * The snapshot is intentionally **not** `suspend`; the production Android
 * implementation reads from [android.net.ConnectivityManager.getLinkProperties]
 * synchronously, and the only async path we need is the *change* signal
 * (which goes through the [MutableSharedFlow] below).
 */
open class InMemoryGuestDnsObserver(
    private val snapshot: () -> GuestDnsConfig
) : GuestDnsObserver {
    private val changes = MutableSharedFlow<GuestDnsConfig>(replay = 1, extraBufferCapacity = 16)

    init {
        // Prime the flow so subscribers always see a value immediately.
        changes.tryEmit(snapshot())
    }

    override fun current(): GuestDnsConfig = snapshot()

    override fun observe(): Flow<GuestDnsConfig> = changes.asSharedFlow()

    override suspend fun refresh() {
        publish()
    }

    /**
     * Pretend the network just changed: re-snapshot and publish. Returns
     * the new config so tests can assert on it.
     */
    suspend fun signalChange(): GuestDnsConfig = publish()

    /**
     * Force a fresh publish. Used by [init], [refresh], and [signalChange].
     * Returns the value that was just pushed to [changes].
     */
    private fun publish(): GuestDnsConfig {
        val next = snapshot()
        changes.tryEmit(next)
        return next
    }
}
