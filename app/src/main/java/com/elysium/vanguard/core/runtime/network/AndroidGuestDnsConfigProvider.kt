package com.elysium.vanguard.core.runtime.network

import android.content.Context
import android.net.ConnectivityManager
import java.net.InetAddress

/** Reads DNS from Android's active network; it never falls back to public DNS. */
class AndroidGuestDnsConfigProvider(context: Context) : GuestDnsConfigProvider {
    private val connectivityManager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun current(): GuestDnsConfig {
        val network = connectivityManager.activeNetwork ?: return GuestDnsConfig.EMPTY
        val properties = connectivityManager.getLinkProperties(network) ?: return GuestDnsConfig.EMPTY
        val nameservers = properties.dnsServers
            .mapNotNull { server: InetAddress -> server.hostAddress?.substringBefore('%') }
            .distinct()
        val domains = properties.domains ?: ""
        val searchDomains = domains
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .distinct()
        return GuestDnsConfig(nameservers = nameservers, searchDomains = searchDomains)
    }
}
