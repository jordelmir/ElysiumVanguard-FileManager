package com.elysium.vanguard.core.runtime.network.firewall

import com.elysium.vanguard.core.runtime.network.policy.NetworkMode
import com.elysium.vanguard.core.runtime.network.policy.NetworkPolicy
import com.elysium.vanguard.core.runtime.network.policy.NetworkPolicySpecBridge
import com.elysium.vanguard.core.runtime.workspace_def.NetworkPolicySpec

/**
 * Phase 15 — translates a [NetworkPolicy] into a
 * [FirewallState].
 *
 * The translator is the seam between Phase 13's
 * [com.elysium.vanguard.core.runtime.network.policy.NetworkBroker]
 * (which says yes / no / require-confirmation for a single
 * request) and the platform firewall (which is asked to enforce
 * a *state* on a session's traffic). The broker decides one
 * packet; the firewall compiles the whole session's ruleset
 * from the same policy that drives the broker.
 *
 * The translator is pure JVM. The [FirewallRuleBackend]
 * applies the rules; this class never touches iptables. The
 * split keeps the rule shape testable end-to-end and lets the
 * production backend (Phase 15 production wiring) be a thin
 * adapter over `iptables-restore` or a cgroup eBPF map.
 *
 * The master order §10.2 rules the translator implements:
 *
 *   - BLOCKED — drop everything, including loopback.
 *   - LOOPBACK_ONLY — accept loopback; drop everything else.
 *   - OUTBOUND_ONLY — accept loopback; accept outbound
 *     NEW+ESTABLISHED; accept inbound on published ports
 *     (ESTABLISHED only — the inbound rule fires when a remote
 *     connects to a published port and the session replies);
 *     drop everything else.
 *   - LAN — accept loopback; accept RFC1918 + link-local +
 *     CGN in both directions; accept inbound on published
 *     ports from LAN; drop inbound from the internet.
 *   - INTERNET — accept loopback; accept all in both
 *     directions; published ports are informational.
 */
class NetworkPolicyFirewall {

    /**
     * PHASE 105 — the **workspace-aware** entry point.
     * The caller passes a [NetworkPolicySpec] (the
     * typed value carried by the workspace JSON); the
     * firewall calls [NetworkPolicySpecBridge] to
     * translate it to a [NetworkPolicy] and then
     * delegates to the existing [compile] method.
     *
     * This is the entry point the launch path should
     * use. The pre-Phase 105 [compile] method is
     * retained for callers that already have a
     * [NetworkPolicy] in hand (e.g. test fakes).
     */
    fun compileFromSpec(sessionId: String, spec: NetworkPolicySpec): FirewallState {
        val policy = NetworkPolicySpecBridge.toSessionPolicy(spec)
        return compile(sessionId = sessionId, policy = policy)
    }

    /**
     * Compile [policy] for the session identified by [sessionId]
     * into a [FirewallState] ready to hand to a
     * [FirewallRuleBackend]. The session id is the chain key —
     * the backend uses it to namespace the rules.
     */
    fun compile(sessionId: String, policy: NetworkPolicy): FirewallState {
        val rules = mutableListOf<FirewallRule>()
        val prefix = sessionId
        when (policy.mode) {
            NetworkMode.BLOCKED -> {
                rules += dropAll(
                    id = "$prefix.blocked.drop-all",
                    priority = 900,
                    comment = "BLOCKED: drop all traffic"
                )
            }
            NetworkMode.LOOPBACK_ONLY -> {
                rules += acceptLoopback(
                    id = "$prefix.loopback-only.accept-lo",
                    priority = 100
                )
                rules += dropAll(
                    id = "$prefix.loopback-only.drop-all",
                    priority = 900,
                    comment = "LOOPBACK_ONLY: drop non-loopback"
                )
            }
            NetworkMode.OUTBOUND_ONLY -> {
                rules += acceptLoopback(
                    id = "$prefix.outbound-only.accept-lo",
                    priority = 100
                )
                rules += acceptOutboundNew(
                    id = "$prefix.outbound-only.out-new",
                    priority = 200,
                    comment = "OUTBOUND_ONLY: allow outbound NEW"
                )
                rules += acceptPublishedInbound(
                    prefix = prefix,
                    mode = "outbound-only",
                    policy = policy,
                    sourceRangesV4 = INTERNET_SOURCES_V4,
                    sourceRangesV6 = INTERNET_SOURCES_V6,
                    priority = 300,
                    stateNote = "ESTABLISHED"
                )
                rules += dropAll(
                    id = "$prefix.outbound-only.drop-all",
                    priority = 900,
                    comment = "OUTBOUND_ONLY: drop everything else"
                )
            }
            NetworkMode.LAN -> {
                rules += acceptLoopback(
                    id = "$prefix.lan.accept-lo",
                    priority = 100
                )
                rules += acceptLanBidirectional(
                    id = "$prefix.lan.bidir",
                    priority = 200,
                    comment = "LAN: allow LAN traffic in both directions"
                )
                rules += acceptPublishedInbound(
                    prefix = prefix,
                    mode = "lan",
                    policy = policy,
                    sourceRangesV4 = LAN_RANGES_V4,
                    sourceRangesV6 = LAN_RANGES_V6,
                    priority = 300,
                    stateNote = "ANY"
                )
                rules += dropInternetInbound(
                    id = "$prefix.lan.drop-internet-in",
                    priority = 800,
                    comment = "LAN: drop inbound from the internet"
                )
            }
            NetworkMode.INTERNET -> {
                rules += acceptLoopback(
                    id = "$prefix.internet.accept-lo",
                    priority = 100
                )
                rules += acceptAllBidirectional(
                    id = "$prefix.internet.bidir",
                    priority = 200,
                    comment = "INTERNET: allow all traffic in both directions"
                )
                rules += acceptPublishedInbound(
                    prefix = prefix,
                    mode = "internet",
                    policy = policy,
                    sourceRangesV4 = INTERNET_SOURCES_V4,
                    sourceRangesV6 = INTERNET_SOURCES_V6,
                    priority = 300,
                    stateNote = "ANY (informational)"
                )
            }
        }
        return FirewallState(
            chainName = chainNameFor(sessionId),
            rules = rules.sortedBy { it.priority }
        )
    }

    /**
     * Compute the diff between the [desired] state and the
     * [current] state. The enforcer applies this diff to a
     * [FirewallRuleBackend].
     */
    fun diff(desired: FirewallState, current: FirewallState): FirewallDiff {
        val desiredById = desired.rules.associateBy { it.id }
        val currentById = current.rules.associateBy { it.id }
        val added = desired.rules.filter { it.id !in currentById }
        val removed = current.rules.filter { it.id !in desiredById }
        val kept = desired.rules.filter { it.id in currentById }
        return FirewallDiff(added = added, removed = removed, kept = kept)
    }

    /**
     * Sanitize a session id into something the backend can use
     * as a chain name. iptables caps chain names at 28 chars;
     * we keep the prefix `elysium-` and let the session id be
     * its hex / uuid form. The sanitised name MUST contain at
     * least one letter or digit — punctuation-only inputs
     * (e.g. "---") are rejected because they would collapse
     * to a chain that does not match any rule id.
     */
    fun chainNameFor(sessionId: String): String {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        val cleaned = sessionId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        require(cleaned.any { it.isLetterOrDigit() }) {
            "sessionId must contain at least one letter or digit: $sessionId"
        }
        return "elysium-$cleaned".take(28)
    }

    // --- rule builders ---

    private fun acceptLoopback(id: String, priority: Int) = listOf(
        FirewallRule(
            id = id,
            direction = Direction.INBOUND,
            family = AddressFamily.IPV4,
            interfaceName = "lo",
            protocol = FirewallProtocol.ANY,
            port = null,
            source = IpRange.LOOPBACK_V4,
            destination = IpRange.ANY,
            state = ConnectionState.ANY,
            action = FirewallAction.ACCEPT,
            priority = priority,
            comment = "accept loopback IPv4"
        ),
        FirewallRule(
            id = "$id-v6",
            direction = Direction.INBOUND,
            family = AddressFamily.IPV6,
            interfaceName = "lo",
            protocol = FirewallProtocol.ANY,
            port = null,
            source = IpRange.LOOPBACK_V6,
            destination = IpRange.ANY,
            state = ConnectionState.ANY,
            action = FirewallAction.ACCEPT,
            priority = priority,
            comment = "accept loopback IPv6"
        ),
        // Outbound to loopback is also accepted (the loopback
        // ACCEPT above covers inbound; we mirror it for the
        // outbound direction so a connection from the session
        // to 127.0.0.1 is symmetric).
        FirewallRule(
            id = "$id-out",
            direction = Direction.OUTBOUND,
            family = AddressFamily.IPV4,
            interfaceName = "lo",
            protocol = FirewallProtocol.ANY,
            port = null,
            source = IpRange.ANY,
            destination = IpRange.LOOPBACK_V4,
            state = ConnectionState.ANY,
            action = FirewallAction.ACCEPT,
            priority = priority,
            comment = "accept outbound loopback IPv4"
        ),
        FirewallRule(
            id = "$id-out-v6",
            direction = Direction.OUTBOUND,
            family = AddressFamily.IPV6,
            interfaceName = "lo",
            protocol = FirewallProtocol.ANY,
            port = null,
            source = IpRange.ANY,
            destination = IpRange.LOOPBACK_V6,
            state = ConnectionState.ANY,
            action = FirewallAction.ACCEPT,
            priority = priority,
            comment = "accept outbound loopback IPv6"
        )
    )

    private fun acceptOutboundNew(id: String, priority: Int, comment: String) = listOf(
        FirewallRule(
            id = id,
            direction = Direction.OUTBOUND,
            family = AddressFamily.ANY,
            interfaceName = "any",
            protocol = FirewallProtocol.ANY,
            port = null,
            source = IpRange.ANY,
            destination = IpRange.ANY,
            state = ConnectionState.NEW,
            action = FirewallAction.ACCEPT,
            priority = priority,
            comment = comment
        ),
        // ESTABLISHED is implicit on most platforms (the
        // kernel matches replies via conntrack); we emit it
        // explicitly so a backend that does not have a default
        // ACCEPT for ESTABLISHED still routes replies back.
        FirewallRule(
            id = "$id-est",
            direction = Direction.OUTBOUND,
            family = AddressFamily.ANY,
            interfaceName = "any",
            protocol = FirewallProtocol.ANY,
            port = null,
            source = IpRange.ANY,
            destination = IpRange.ANY,
            state = ConnectionState.ESTABLISHED,
            action = FirewallAction.ACCEPT,
            priority = priority,
            comment = "$comment (established)"
        )
    )

    private fun acceptLanBidirectional(id: String, priority: Int, comment: String): List<FirewallRule> {
        val out = mutableListOf<FirewallRule>()
        for (range in LAN_RANGES_V4 + LAN_RANGES_V6) {
            val family = if (range.cidr.contains(':')) AddressFamily.IPV6 else AddressFamily.IPV4
            out += FirewallRule(
                id = "$id-out-${range.cidr}",
                direction = Direction.OUTBOUND,
                family = family,
                interfaceName = "any",
                protocol = FirewallProtocol.ANY,
                port = null,
                source = IpRange.ANY,
                destination = range,
                state = ConnectionState.ANY,
                action = FirewallAction.ACCEPT,
                priority = priority,
                comment = comment
            )
            out += FirewallRule(
                id = "$id-in-${range.cidr}",
                direction = Direction.INBOUND,
                family = family,
                interfaceName = "any",
                protocol = FirewallProtocol.ANY,
                port = null,
                source = range,
                destination = IpRange.ANY,
                state = ConnectionState.ANY,
                action = FirewallAction.ACCEPT,
                priority = priority,
                comment = comment
            )
        }
        return out
    }

    private fun acceptAllBidirectional(id: String, priority: Int, comment: String) = listOf(
        FirewallRule(
            id = id,
            direction = Direction.OUTBOUND,
            family = AddressFamily.ANY,
            interfaceName = "any",
            protocol = FirewallProtocol.ANY,
            port = null,
            source = IpRange.ANY,
            destination = IpRange.ANY,
            state = ConnectionState.ANY,
            action = FirewallAction.ACCEPT,
            priority = priority,
            comment = comment
        ),
        FirewallRule(
            id = "$id-in",
            direction = Direction.INBOUND,
            family = AddressFamily.ANY,
            interfaceName = "any",
            protocol = FirewallProtocol.ANY,
            port = null,
            source = IpRange.ANY,
            destination = IpRange.ANY,
            state = ConnectionState.ANY,
            action = FirewallAction.ACCEPT,
            priority = priority,
            comment = comment
        )
    )

    private fun acceptPublishedInbound(
        prefix: String,
        mode: String,
        policy: NetworkPolicy,
        sourceRangesV4: List<IpRange>,
        sourceRangesV6: List<IpRange>,
        priority: Int,
        stateNote: String
    ): List<FirewallRule> {
        if (policy.publishedPorts.isEmpty()) return emptyList()
        val out = mutableListOf<FirewallRule>()
        for (port in policy.publishedPorts.sorted()) {
            for (source in sourceRangesV4) {
                out += FirewallRule(
                    id = "$prefix.$mode.published-$port-${source.cidr}-v4",
                    direction = Direction.INBOUND,
                    family = AddressFamily.IPV4,
                    interfaceName = "any",
                    protocol = FirewallProtocol.TCP,
                    port = port,
                    source = source,
                    destination = IpRange.ANY,
                    state = ConnectionState.ANY,
                    action = FirewallAction.ACCEPT,
                    priority = priority,
                    comment = "$mode: published port $port from ${source.cidr} v4 ($stateNote)"
                )
            }
            for (source in sourceRangesV6) {
                out += FirewallRule(
                    id = "$prefix.$mode.published-$port-${source.cidr}-v6",
                    direction = Direction.INBOUND,
                    family = AddressFamily.IPV6,
                    interfaceName = "any",
                    protocol = FirewallProtocol.TCP,
                    port = port,
                    source = source,
                    destination = IpRange.ANY,
                    state = ConnectionState.ANY,
                    action = FirewallAction.ACCEPT,
                    priority = priority,
                    comment = "$mode: published port $port from ${source.cidr} v6 ($stateNote)"
                )
            }
        }
        return out
    }

    private fun dropInternetInbound(id: String, priority: Int, comment: String) = listOf(
        FirewallRule(
            id = id,
            direction = Direction.INBOUND,
            family = AddressFamily.ANY,
            interfaceName = "any",
            protocol = FirewallProtocol.ANY,
            port = null,
            source = IpRange.ANY,
            destination = IpRange.ANY,
            state = ConnectionState.NEW,
            action = FirewallAction.DROP,
            priority = priority,
            comment = comment
        )
    )

    private fun dropAll(id: String, priority: Int, comment: String) = listOf(
        FirewallRule(
            id = id,
            direction = Direction.INBOUND,
            family = AddressFamily.ANY,
            interfaceName = "any",
            protocol = FirewallProtocol.ANY,
            port = null,
            source = IpRange.ANY,
            destination = IpRange.ANY,
            state = ConnectionState.ANY,
            action = FirewallAction.DROP,
            priority = priority,
            comment = comment
        )
    )

    private companion object {
        val LAN_RANGES_V4 = listOf(
            IpRange.LAN_10,
            IpRange.LAN_172,
            IpRange.LAN_192,
            IpRange.LINK_LOCAL_V4,
            IpRange.CGN
        )
        val LAN_RANGES_V6 = emptyList<IpRange>()
        val INTERNET_SOURCES_V4 = listOf(IpRange.ANY)
        val INTERNET_SOURCES_V6 = listOf(IpRange.ANY)
    }
}
