package com.elysium.vanguard.core.runtime.network

import kotlinx.coroutines.flow.Flow

/**
 * Reactive view of the Android network resolver state.
 *
 * The PRoot guest's [GuestDnsConfig] must follow the device's *active* network,
 * not whatever was current when the launcher first ran. The master order §10.1
 * requires that we re-derive the resolver on:
 *
 *   - session start;
 *   - Wi-Fi → mobile data switch (and reverse);
 *   - VPN up / VPN down;
 *   - private DNS change (the `1.1.1.1` style system override);
 *   - network loss + recovery.
 *
 * The interface extends the existing one-shot [GuestDnsConfigProvider] so any
 * caller that asks for a snapshot keeps working unchanged. Callers that want
 * the live stream use [observe] and collect the flow.
 *
 * `refresh()` is `suspend` rather than fire-and-forget so the launcher can
 * force a re-read at command-build time even if Android has not yet posted a
 * NetworkCallback tick.
 */
interface GuestDnsObserver : GuestDnsConfigProvider {
    /**
     * Hot flow that emits the current config immediately, then re-emits on
     * every detected network change. The flow is conflated by intent: a
     * burst of changes yields the *latest* resolver state, not every
     * intermediate one.
     */
    fun observe(): Flow<GuestDnsConfig>

    /**
     * Force a re-read of the active network's link properties and publish
     * the new config to [observe] subscribers. Safe to call from any
     * coroutine context.
     */
    suspend fun refresh()

    companion object {
        /** Marker for "we tried to read the network, but there is none active". */
        val NO_NETWORK: GuestDnsConfig = GuestDnsConfig.EMPTY
    }
}
