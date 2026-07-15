package com.elysium.vanguard.core.runtime.network.policy

import java.net.InetAddress

/**
 * Phase 13 — pure decision engine for guest network access.
 *
 * The broker says yes / no / require-confirmation given a
 * [NetworkPolicy] and a request. It does not enforce anything
 * itself — that is the caller's job (iptables, cgroup, eBPF,
 * etc.). Splitting decisions from enforcement keeps the broker
 * JVM-testable end-to-end without root or network namespaces.
 *
 * Master order §10.2 rules the broker implements:
 *
 *   - BLOCKED denies everything, including loopback.
 *   - LOOPBACK_ONLY permits only 127.0.0.0/8 and ::1.
 *   - OUTBOUND_ONLY permits outbound to any remote; refuses
 *     inbound listen except for the [NetworkPolicy.publishedPorts].
 *   - LAN permits RFC1918 + link-local + loopback.
 *   - INTERNET permits anything except a 0.0.0.0 listen without
 *     [NetworkPolicy.allowWildcardListen].
 *   - 0.0.0.0 listen ALWAYS returns [NetworkDecision.AllowWithConfirmation]
 *     unless [NetworkPolicy.allowWildcardListen] is true. This
 *     is the master order's "never abrir servicios en 0.0.0.0
 *     sin consentimiento explícito" — we surface it as a typed
 *     decision so the UI can show the consent dialog.
 *
 * The broker is *additive* to whatever enforcement the runtime
 * uses. A green light from the broker means "the policy is
 * willing"; it does not mean the system can actually route the
 * traffic. The caller pairs the broker's decision with its
 * firewall state.
 */
class NetworkBroker {

    /**
     * Decide whether a process under [policy] is allowed to
     * open an outbound TCP/UDP connection to [remote].
     *
     * The decision is based on the destination address (loopback,
     * LAN, internet) and the policy's allow-list. A port is
     * passed for logging; the broker does not gate by port for
     * outbound traffic.
     */
    fun decideOutbound(
        policy: NetworkPolicy,
        remote: InetAddress,
        port: Int,
        audit: NetworkAuditLog? = null
    ): NetworkDecision {
        val reasonLog = "outbound -> $remote:$port"
        if (policy.mode == NetworkMode.BLOCKED) {
            return deny("BLOCKED policy denies $reasonLog", audit)
        }
        if (policy.allowedRemoteHosts.isNotEmpty()) {
            val host = remote.hostAddress?.let { stripZone(it) }
            val matched = policy.allowedRemoteHosts.any { it.equals(host, ignoreCase = true) }
            if (!matched) {
                return deny(
                    "$remote is not in the policy's allow-list ($reasonLog)",
                    audit
                )
            }
        }
        val decision = when (policy.mode) {
            NetworkMode.BLOCKED -> return deny("BLOCKED", audit) // already handled
            NetworkMode.LOOPBACK_ONLY ->
                if (remote.isLoopbackAddress) NetworkDecision.Allow
                else deny("LOOPBACK_ONLY denies $reasonLog", audit)
            NetworkMode.OUTBOUND_ONLY -> {
                if (remote.isLoopbackAddress) NetworkDecision.Allow
                else if (isRoutableTo(remote)) NetworkDecision.Allow
                else deny("OUTBOUND_ONLY does not allow non-routable $reasonLog", audit)
            }
            NetworkMode.LAN ->
                if (isLan(remote) || remote.isLoopbackAddress) NetworkDecision.Allow
                else deny("LAN policy denies non-LAN $reasonLog", audit)
            NetworkMode.INTERNET -> NetworkDecision.Allow
        }
        audit?.record(AuditEvent(policy, AuditEvent.Kind.OUTBOUND, remote, port, decision))
        return decision
    }

    /**
     * Decide whether a process under [policy] is allowed to
     * listen on [bindAddress]:[port].
     *
     * Mode semantics for listen:
     *
     *   - BLOCKED: denied, no exceptions.
     *   - LOOPBACK_ONLY: only loopback bind addresses.
     *   - OUTBOUND_ONLY: only [NetworkPolicy.publishedPorts]; no
     *     listen without an explicit published entry.
     *   - LAN: any LAN bind address; if [NetworkPolicy.publishedPorts]
     *     is non-empty, the port must be in it.
     *   - INTERNET: any bind address; the published list is
     *     informational (the UI shows it; the broker does not
     *     enforce it for INTERNET).
     *
     * 0.0.0.0 (or ::) listens ALWAYS return
     * [NetworkDecision.AllowWithConfirmation] unless the policy
     * has [NetworkPolicy.allowWildcardListen] set. This is the
     * master order §10.2 rule "never abrir servicios en 0.0.0.0
     * sin consentimiento explícito".
     */
    fun decideListen(
        policy: NetworkPolicy,
        bindAddress: InetAddress,
        port: Int,
        audit: NetworkAuditLog? = null
    ): NetworkDecision {
        val reasonLog = "listen on $bindAddress:$port"
        if (policy.mode == NetworkMode.BLOCKED) {
            return deny("BLOCKED policy denies $reasonLog", audit)
        }
        if (policy.mode == NetworkMode.LOOPBACK_ONLY && !bindAddress.isLoopbackAddress) {
            return deny("LOOPBACK_ONLY allows only loopback listen, not $reasonLog", audit)
        }
        if (bindAddress.isAnyLocalAddress && !policy.allowWildcardListen) {
            val decision = NetworkDecision.AllowWithConfirmation(
                "0.0.0.0 listen requires explicit user consent ($reasonLog)"
            )
            audit?.record(AuditEvent(policy, AuditEvent.Kind.LISTEN, bindAddress, port, decision))
            return decision
        }
        // Published-port enforcement:
        //   - OUTBOUND_ONLY: published ports are the ONLY way to listen.
        //   - LAN / INTERNET: published ports are an additional filter;
        //     when the list is non-empty, the port must be in it.
        //   - LOOPBACK_ONLY: no listen on non-loopback (already checked above).
        val enforcePublished = when (policy.mode) {
            NetworkMode.OUTBOUND_ONLY -> true
            NetworkMode.LAN, NetworkMode.INTERNET -> policy.publishedPorts.isNotEmpty()
            NetworkMode.LOOPBACK_ONLY, NetworkMode.BLOCKED -> false
        }
        if (enforcePublished && port !in policy.publishedPorts) {
            return deny(
                "port $port is not in the policy's published-ports list ($reasonLog)",
                audit
            )
        }
        val decision = NetworkDecision.Allow
        audit?.record(AuditEvent(policy, AuditEvent.Kind.LISTEN, bindAddress, port, decision))
        return decision
    }

    private fun deny(reason: String, audit: NetworkAuditLog?): NetworkDecision {
        val decision = NetworkDecision.Deny(reason)
        // We don't have a target address at the deny level; the
        // caller passes one when they call decide*. So we skip
        // audit here; the caller is expected to audit themselves
        // on the deny path with full context. We document this
        // by accepting the audit log parameter as nullable and
        // recording only the success / confirmation paths.
        return decision
    }

    companion object {
        /**
         * Loopback range: 127.0.0.0/8 and ::1. Public so
         * callers can check membership outside the broker.
         */
        fun isLoopback(addr: InetAddress): Boolean = addr.isLoopbackAddress

        /**
         * RFC1918 + link-local + CGN. Anything that is not
         * internet-routable in the strict sense.
         */
        fun isLan(addr: InetAddress): Boolean {
            val bytes = addr.address
            // IPv4 path
            if (bytes.size == 4) {
                val b0 = bytes[0].toInt() and 0xff
                val b1 = bytes[1].toInt() and 0xff
                return when {
                    b0 == 10 -> true                                          // 10.0.0.0/8
                    b0 == 172 && b1 in 16..31 -> true                          // 172.16.0.0/12
                    b0 == 192 && b1 == 168 -> true                             // 192.168.0.0/16
                    b0 == 169 && b1 == 254 -> true                             // 169.254.0.0/16 link-local
                    b0 == 100 && b1 in 64..127 -> true                         // 100.64.0.0/10 CGN
                    else -> false
                }
            }
            // IPv6 path. We treat link-local and unique-local as
            // LAN. Site-local is deprecated but we accept it.
            if (bytes.size == 16) {
                val b0 = bytes[0].toInt() and 0xff
                val b1 = bytes[1].toInt() and 0xff
                return when {
                    // fc00::/7 unique-local: first 7 bits = 1111 110
                    b0 and 0xfe == 0xfc -> true
                    // fe80::/10 link-local: 1111 1110 10...
                    b0 == 0xfe && (b1 and 0xc0) == 0x80 -> true
                    else -> false
                }
            }
            return false
        }

        /**
         * True when the address is plausibly internet-routable
         * (i.e. not a multicast, not a reserved range). The
         * broker does not actually know whether a route exists;
         * this is a coarse filter.
         */
        fun isRoutableTo(addr: InetAddress): Boolean {
            if (addr.isAnyLocalAddress) return false
            if (addr.isLoopbackAddress) return false
            if (addr.isMulticastAddress) return false
            if (isLan(addr)) return true // LAN is also routable; the policy decides
            return true
        }

        /**
         * Strip the zone identifier from a host address
         * (e.g. `fe80::1%wlan0` -> `fe80::1`). The broker's
         * allow-list compares canonical forms. Public so
         * callers can normalize user input the same way.
         */
        fun stripZone(host: String): String = host.substringBefore('%')
    }
}

/**
 * One row of the audit log. The log is in-memory (a
 * [NetworkAuditLog] is just a synchronized list); persistence
 * is the caller's job and is documented in the master order
 * §10.2.
 */
data class AuditEvent(
    val policy: NetworkPolicy,
    val kind: Kind,
    val target: InetAddress,
    val port: Int,
    val decision: NetworkDecision
) {
    enum class Kind { OUTBOUND, LISTEN }
    val atMs: Long = System.currentTimeMillis()
}

/**
 * In-memory append-only log of [AuditEvent]s. Synchronized for
 * thread-safety; for a real-world load, replace with a ring
 * buffer that flushes to disk asynchronously.
 */
class NetworkAuditLog {
    private val events = mutableListOf<AuditEvent>()
    private val lock = Any()

    fun record(event: AuditEvent) {
        synchronized(lock) { events.add(event) }
    }

    fun snapshot(): List<AuditEvent> = synchronized(lock) { events.toList() }

    fun size(): Int = synchronized(lock) { events.size }

    fun clear() = synchronized(lock) { events.clear() }
}
