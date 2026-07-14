package com.elysium.vanguard.core.runtime.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Test-friendly and fallback implementation of [GuestDnsObserver].
 *
 * The snapshot comes from a caller-supplied lambda, and the change signal
 * comes from either [signalChange] (programmatic) or [refresh]. Tests use
 * this to simulate Wi-Fi → data flips without a real [android.net.ConnectivityManager].
 *
 * Backed by a [MutableStateFlow] rather than a [kotlinx.coroutines.flow.MutableSharedFlow]
 * because StateFlow is *conflated*: a fast publisher that emits faster
 * than the consumer drains loses intermediate values, but the consumer
 * always sees the latest. This is exactly the semantics the DNS pipeline
 * wants — we only care about distinct values, and the tracker's
 * `distinctUntilChanged` will discard duplicates anyway. Using
 * SharedFlow here would force tests to add `yield()` between every
 * `signalChange` call, because the buffer has a fixed capacity and a
 * fast publisher would overflow it.
 *
 * Contract mirrors the production [AndroidGuestDnsConfigProvider]:
 *
 *   - [current] always returns the *freshest* snapshot (no caching).
 *     This is what the launcher calls when it builds a proot command;
 *     the caller is responsible for cache invalidation, not us.
 *   - [observe] exposes a hot flow that always reflects the current
 *     state to new subscribers and re-emits on every [signalChange]
 *     or [refresh] call.
 *
 * The snapshot is intentionally **not** `suspend`; the production Android
 * implementation reads from [android.net.ConnectivityManager.getLinkProperties]
 * synchronously, and the only async path we need is the *change* signal
 * (which goes through the [MutableStateFlow] below).
 */
open class InMemoryGuestDnsObserver(
    private val snapshot: () -> GuestDnsConfig
) : GuestDnsObserver {
    private val state = MutableStateFlow(snapshot())

    override fun current(): GuestDnsConfig = snapshot()

    override fun observe(): Flow<GuestDnsConfig> = state.asStateFlow()

    override suspend fun refresh() {
        publish()
    }

    /**
     * Pretend the network just changed: re-snapshot and publish. Returns
     * the new config so tests can assert on it.
     */
    suspend fun signalChange(): GuestDnsConfig = publish()

    private fun publish(): GuestDnsConfig {
        val next = snapshot()
        state.value = next
        return next
    }
}
