package com.elysium.vanguard.core.runtime.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart

/**
 * Android implementation of [GuestDnsObserver].
 *
 * Re-derives the resolver config on every relevant [ConnectivityManager]
 * event:
 *
 *   - [ConnectivityManager.NetworkCallback.onAvailable] → link properties
 *     now valid; re-read.
 *   - [ConnectivityManager.NetworkCallback.onLost] → no active network;
 *     emit [GuestDnsConfig.EMPTY] so consumers do not hold stale servers.
 *   - [ConnectivityManager.NetworkCallback.onCapabilitiesChanged] →
 *     validated status or transport type may have changed (e.g. Wi-Fi
 *     joined, validated, dropped to captive portal).
 *   - [ConnectivityManager.NetworkCallback.onLinkPropertiesChanged] →
 *     DNS server list changed (private DNS, VPN, etc.).
 *
 * The class is registered against the *default* network so we mirror what
 * the system uses for outbound traffic, not a specific transport.
 *
 * Lifecycle: callers MUST invoke [shutdown] when the observer is no longer
 * needed (process teardown, Hilt component disposal). Failing to unregister
 * the callback leaks a binder reference inside the system server.
 */
class AndroidGuestDnsObserver(context: Context) : GuestDnsObserver {
    private val connectivityManager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Internal bus of "the active network's properties may have changed; please
     * re-read". Distinct from the published [observe] flow so a slow consumer
     * cannot block the underlying callback chain.
     */
    private val changeBus = MutableSharedFlow<GuestDnsConfig>(extraBufferCapacity = 16)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            publish()
        }

        override fun onLost(network: Network) {
            // We only care about the *default* network; if it was the
            // active one at the time, [publish] will return [GuestDnsConfig.EMPTY].
            publish()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            publish()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            publish()
        }
    }

    init {
        // Default network request: any validated, internet-capable transport.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivityManager.registerDefaultNetworkCallback(callback) }
            .onFailure { /* Older devices may lack the default-network API; the snapshot path still works. */ }
    }

    @Volatile
    private var lastPublished: GuestDnsConfig = snapshot()

    override fun current(): GuestDnsConfig = snapshot()

    override fun observe(): Flow<GuestDnsConfig> = flow {
        // Emit whatever we have on subscribe, then merge in subsequent
        // change signals. We do not start the [changeBus] cold — Android
        // may have already posted events before this collect.
        emit(lastPublished)
        changeBus.collect { emit(it) }
    }.onStart {
        // Trigger an initial refresh in case the bus has not been seeded.
        publish()
    }

    override suspend fun refresh() {
        publish()
    }

    /** Read the active network's resolver data and push to the bus. */
    private fun publish() {
        val next = snapshot()
        lastPublished = next
        changeBus.tryEmit(next)
    }

    /**
     * Pull a [GuestDnsConfig] from the current default network. Returns
     * [GuestDnsConfig.EMPTY] when there is no active network — the launcher
     * then skips the bind mount rather than binding an empty resolv.conf.
     */
    private fun snapshot(): GuestDnsConfig {
        val network = connectivityManager.activeNetwork ?: return GuestDnsConfig.EMPTY
        val properties = connectivityManager.getLinkProperties(network) ?: return GuestDnsConfig.EMPTY
        val rawAddresses = properties.dnsServers.mapNotNull { it.hostAddress }
        return buildGuestDnsConfig(
            rawHostAddresses = rawAddresses,
            domains = properties.domains
        )
    }

    /**
     * Stop receiving network callbacks. Idempotent; safe to call more than
     * once. The class continues to function for [current] / [refresh] but
     * no longer reacts to system changes.
     */
    fun shutdown() {
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }
}
