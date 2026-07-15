package com.elysium.vanguard.core.runtime.network.policy

/**
 * Phase 13 — network policy for a guest rootfs.
 *
 * Master order §10.2 lists the network shapes a guest can be
 * granted:
 *
 *   - solo loopback
 *   - outbound permitido
 *   - LAN permitido
 *   - puerto publicado (subordinate to outbound)
 *   - internet bloqueado
 *   - red por aplicación (per-distro policy)
 *   - confirmación para escuchar en interfaces externas
 *   - nunca abrir servicios en 0.0.0.0 sin consentimiento explícito
 *
 * The policy is a value type. Decisions are made by
 * [com.elysium.vanguard.core.runtime.network.policy.NetworkBroker].
 * Enforcement (iptables, cgroup, eBPF, etc.) is the caller's
 * job — this layer only says yes / no / require-confirmation.
 */
data class NetworkPolicy(
    val mode: NetworkMode,
    /**
     * Inbound ports the guest is allowed to listen on, in
     * addition to whatever [mode] implies. Empty for everything
     * except [NetworkMode.PUBLISHED_PORT] and similar.
     */
    val publishedPorts: Set<Int> = emptySet(),
    /**
     * Host names / IP literals the guest is allowed to reach.
     * Empty means "any host"; the policy still applies at the
     * mode level (loopback / LAN / internet).
     */
    val allowedRemoteHosts: Set<String> = emptySet(),
    /**
     * Whether the user has explicitly accepted a 0.0.0.0 listen
     * during this session. Defaults to false; the broker refuses
     * any 0.0.0.0 listen until the user toggles this on.
     */
    val allowWildcardListen: Boolean = false
) {
    init {
        require(publishedPorts.all { it in 1..65535 }) {
            "publishedPorts must be valid TCP/UDP port numbers: $publishedPorts"
        }
        require(allowedRemoteHosts.none { it.isBlank() }) {
            "allowedRemoteHosts must not contain blank entries"
        }
    }
}

/**
 * Coarse network shape. The finer-grained allow-list is on
 * [NetworkPolicy.allowedRemoteHosts]; finer-grained publish
 * allow-list is on [NetworkPolicy.publishedPorts].
 */
enum class NetworkMode {
    /** No network at all. The guest cannot reach loopback either. */
    BLOCKED,

    /** Only 127.0.0.0/8 and ::1. Default for an unknown guest. */
    LOOPBACK_ONLY,

    /** Can connect outbound; cannot listen inbound. */
    OUTBOUND_ONLY,

    /** Can reach the local network (RFC1918 + link-local). */
    LAN,

    /** Full internet, both directions (subject to the publish list). */
    INTERNET
}
