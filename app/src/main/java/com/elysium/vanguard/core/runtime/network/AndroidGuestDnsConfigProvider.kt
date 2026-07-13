package com.elysium.vanguard.core.runtime.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Reads DNS from Android's active network with automatic fallback to
 * well-known public DNS when the system provides none.
 *
 * DNS is the single most common failure in PRoot sessions. Android does
 * not always populate LinkProperties.dnsServers — especially on first
 * boot, during Wi-Fi<->mobile transitions, or when a VPN is active. When
 * that happens the guest gets an empty resolv.conf and every `apt`,
 * `ping`, or `curl` fails with "Temporary failure resolving".
 *
 * This provider solves it by:
 *   1. Reading DNS from the active network's LinkProperties.
 *   2. Falling back to a curated set of well-known public DNS servers
 *      when the system returns zero nameservers.
 *   3. Registering a [ConnectivityManager.NetworkCallback] so that DNS
 *      is re-evaluated on every network change (Wi-Fi join, VPN toggle,
 *      mobile data switch, DNS private change).
 *   4. Exposing a [snapshot] that can be polled by the launcher at
 *      session-creation time without blocking.
 */
class AndroidGuestDnsConfigProvider(context: Context) : GuestDnsConfigProvider {

    private val connectivityManager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Latest DNS configuration, updated by the network callback.
     * The launcher reads this at session-start time; it does not need
     * to register its own listener.
     */
    private val latest = AtomicReference(GuestDnsConfig.EMPTY)

    /** Listeners notified on every network change (empty by default). */
    private val listeners = CopyOnWriteArrayList<(GuestDnsConfig) -> Unit>()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            refresh()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
            refresh()
        }

        override fun onLost(network: Network) {
            refresh()
        }
    }

    init {
        registerNetworkCallback()
        // Seed with the current state so the first read is not EMPTY.
        refresh()
    }

    override fun current(): GuestDnsConfig = latest.get()

    /**
     * Register a listener that is invoked on every DNS-relevant network
     * change. The listener runs on a binder thread — keep it lightweight.
     */
    fun addListener(listener: (GuestDnsConfig) -> Unit) {
        listeners.add(listener)
        // Immediately deliver the current snapshot so the caller does not
        // have to wait for the next network event.
        listener(latest.get())
    }

    fun removeListener(listener: (GuestDnsConfig) -> Unit) {
        listeners.remove(listener)
    }

    private fun refresh() {
        val config = readFromSystem()
        latest.set(config)
        listeners.forEach { listener ->
            try {
                listener(config)
            } catch (_: Exception) {
                // Listener must not crash the callback chain.
            }
        }
    }

    private fun readFromSystem(): GuestDnsConfig {
        val network = connectivityManager.activeNetwork
            ?: return fallbackConfig("no active network")
        val properties = connectivityManager.getLinkProperties(network)
            ?: return fallbackConfig("no link properties")
        val nameservers = properties.dnsServers
            .mapNotNull { server: InetAddress -> server.hostAddress?.substringBefore('%') }
            .filter { it.isNotBlank() }
            .distinct()
        val domains = properties.domains ?: ""
        val searchDomains = domains
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .distinct()
        return if (nameservers.isEmpty()) {
            fallbackConfig("system returned 0 nameservers")
        } else {
            GuestDnsConfig(nameservers = nameservers, searchDomains = searchDomains)
        }
    }

    private fun fallbackConfig(reason: String): GuestDnsConfig {
        // Well-known public DNS. We include Cloudflare and Google
        // because they cover IPv4 and are reachable from almost every
        // Android network. If the user has a private DNS (DoT) active,
        // Android populates LinkProperties and we never reach this path.
        return GuestDnsConfig(
            nameservers = FALLBACK_NAMESERVERS,
            searchDomains = emptyList(),
            source = "fallback ($reason)"
        )
    }

    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (_: Exception) {
            // Some OEM ROMs throw on registerNetworkCallback. We degrade
            // gracefully by keeping the initial DNS snapshot.
        }
    }

    companion object {
        /**
         * Fallback nameservers used when Android provides none. These are
         * the same servers that Android itself falls back to when private
         * DNS is not configured — we mirror that behavior for PRoot.
         */
        private val FALLBACK_NAMESERVERS = listOf(
            "1.1.1.1",       // Cloudflare
            "8.8.8.8",       // Google
            "2606:4700:4700::1111",  // Cloudflare IPv6
            "2001:4860:4860::8888"   // Google IPv6
        )
    }
}
