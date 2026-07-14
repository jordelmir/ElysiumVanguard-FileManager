package com.elysium.vanguard.core.runtime.network

/**
 * Pure builder for [GuestDnsConfig] from raw Android link-property data.
 *
 * Lives outside [AndroidGuestDnsObserver] so the JVM test suite can
 * exercise the translation without a real [android.net.ConnectivityManager]
 * (the master order §33.4 lists "broker protocol" and "protocol decoders"
 * as targets of property-based tests; this is one of them).
 *
 * Rules:
 *
 *   - `rawHostAddresses` carries the canonical text form returned by
 *     [java.net.InetAddress.getHostAddress]; for IPv6 link-local
 *     addresses the caller has already stripped the `%<zone>` suffix
 *     (e.g. `fe80::1%wlan0` → `fe80::1`).
 *   - Blank and whitespace-only entries are dropped.
 *   - The list of nameservers is deduplicated; order is preserved
 *     because the OS picks the first responding server.
 *   - The domains string is split on any whitespace; the same trim
 *     and de-duplication rules apply. Empty / null domains yield an
 *     empty search list.
 *
 * The function takes [String] rather than [java.net.InetAddress]
 * intentionally — `InetAddress` is an Android class and the unit test
 * classpath does not have it. The Android observer converts the
 * [android.net.InetAddress] list to a `List<String>` before calling.
 */
fun buildGuestDnsConfig(
    rawHostAddresses: List<String>,
    domains: String?
): GuestDnsConfig {
    val nameservers = rawHostAddresses
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
    val searchDomains = (domains ?: "")
        .split(Regex("\\s+"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
    return GuestDnsConfig(nameservers = nameservers, searchDomains = searchDomains)
}
