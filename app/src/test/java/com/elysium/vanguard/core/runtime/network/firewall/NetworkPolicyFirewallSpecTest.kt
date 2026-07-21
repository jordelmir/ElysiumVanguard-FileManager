package com.elysium.vanguard.core.runtime.network.firewall

import com.elysium.vanguard.core.runtime.workspace_def.NetworkAccessMode
import com.elysium.vanguard.core.runtime.workspace_def.NetworkPolicySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 105 — JVM tests for [NetworkPolicyFirewall.compileFromSpec].
 *
 * The new entry point bridges the workspace's typed
 * [NetworkPolicySpec] (Phase 104) to the firewall's
 * existing [NetworkPolicy] (Phase 13) compilation
 * path. The tests pin the rule shape per workspace
 * spec so a future refactor cannot widen the network
 * access of a "deny-by-default" workspace.
 */
class NetworkPolicyFirewallSpecTest {

    private val firewall = NetworkPolicyFirewall()

    @Test
    fun `compileFromSpec with DENY_ALL emits the LOOPBACK_ONLY rule shape (not BLOCKED)`() {
        // LOOPBACK_ONLY is the safe default: the
        // workspace can use loopback for local IPC
        // but cannot reach any remote host.
        val state = firewall.compileFromSpec("sess-1", NetworkPolicySpec.DEFAULT)
        assertEquals("elysium-sess-1", state.chainName)
        // LOOPBACK_ONLY emits: accept-loopback v4 + v6
        // (2 rules) + drop-all v4 + v6 (2 rules) +
        // a state-establishment rule (1 rule) = 5.
        // We don't pin the exact count (the firewall
        // can grow rules); we pin the SHAPE:
        //  - has accept-lo v4 + v6
        //  - has at least one drop terminator
        //  - has no INTERNET-shape accept-bidir rule
        val byId = state.rules.associateBy { it.id }
        assertTrue(
            "LOOPBACK_ONLY must have accept-lo v4: ${byId.keys}",
            byId.values.any {
                it.id.endsWith(".accept-lo") && it.family == AddressFamily.IPV4
            }
        )
        assertTrue(
            "LOOPBACK_ONLY must have accept-lo v6: ${byId.keys}",
            byId.values.any {
                it.id.endsWith(".accept-lo-v6") && it.family == AddressFamily.IPV6
            }
        )
        assertTrue(
            "LOOPBACK_ONLY must have a drop terminator: ${byId.keys}",
            byId.values.any { it.action == FirewallAction.DROP }
        )
        // And critically: no INTERNET-shape accept-bidir
        // rule (which would let all traffic through).
        assertTrue(
            "DENY_ALL must NOT have a bidir accept: ${byId.keys}",
            byId.values.none { it.id.endsWith(".bidir") }
        )
    }

    @Test
    fun `compileFromSpec with ALLOW_LIST propagates the host allow-list into the session`() {
        val spec = NetworkPolicySpec(
            mode = NetworkAccessMode.ALLOW_LIST,
            allowedHosts = listOf("api.example.com", "*.cdn.example.com"),
            allowedPorts = setOf(443),
        )
        val state = firewall.compileFromSpec("sess-2", spec)
        // OUTBOUND_ONLY shape = 1 accept-loopback + 1 accept-out-new + 1 accept-published-in + 1 drop = 4 rules.
        // (Exact rule count is the firewall's concern; we verify
        // the policy's allowedRemoteHosts are preserved in the
        // NetworkPolicy the firewall consumed. The simplest
        // way to verify the policy is to re-bridge and assert.)
        val policy = com.elysium.vanguard.core.runtime.network.policy.NetworkPolicySpecBridge
            .toSessionPolicy(spec)
        assertEquals(2, policy.allowedRemoteHosts.size)
        assertTrue(policy.allowedRemoteHosts.contains("api.example.com"))
        assertTrue(policy.allowedRemoteHosts.contains("*.cdn.example.com"))
        assertEquals(setOf(443), policy.publishedPorts)
        // OUTBOUND_ONLY mode (not LAN, not INTERNET).
        assertEquals(
            com.elysium.vanguard.core.runtime.network.policy.NetworkMode.OUTBOUND_ONLY,
            policy.mode
        )
        // And the firewall actually compiled a state.
        assertTrue("state should have at least one rule", state.rules.isNotEmpty())
    }

    @Test
    fun `compileFromSpec with ALLOW_ALL emits the INTERNET rule shape`() {
        val state = firewall.compileFromSpec("sess-3", NetworkPolicySpec.OPEN)
        // INTERNET shape: at least 4 rules
        // (bidir-out v4, bidir-in v4, bidir-out v6,
        // bidir-in v6) plus a state-establishment rule.
        // We pin the SHAPE rather than the exact count.
        val byId = state.rules.associateBy { it.id }
        assertTrue(
            "INTERNET must accept all outbound: ${byId.keys}",
            byId.values.any {
                it.id.endsWith(".bidir") && it.direction == Direction.OUTBOUND
            }
        )
        assertTrue(
            "INTERNET must accept all inbound: ${byId.keys}",
            byId.values.any {
                it.id.endsWith(".bidir-in") && it.direction == Direction.INBOUND
            }
        )
        // No DROP terminator (INTERNET is permissive).
        assertTrue(
            "INTERNET must not have a drop terminator: ${byId.keys}",
            byId.values.none { it.action == FirewallAction.DROP }
        )
    }

    @Test
    fun `compileFromSpec session id namespaces the chain`() {
        val a = firewall.compileFromSpec("workspace-a", NetworkPolicySpec.DEFAULT)
        val b = firewall.compileFromSpec("workspace-b", NetworkPolicySpec.DEFAULT)
        assertEquals("elysium-workspace-a", a.chainName)
        assertEquals("elysium-workspace-b", b.chainName)
    }

    @Test
    fun `compileFromSpec with ALLOW_LIST + dnsAllowed still maps to OUTBOUND_ONLY (not LAN)`() {
        // DNS resolution is allowed but the workspace
        // can only reach the listed hosts. The bridge
        // does NOT promote this to LAN mode (LAN would
        // allow all RFC1918, which the workspace didn't
        // ask for).
        val spec = NetworkPolicySpec(
            mode = NetworkAccessMode.ALLOW_LIST,
            allowedHosts = listOf("api.example.com"),
            dnsAllowed = true,
        )
        val policy = com.elysium.vanguard.core.runtime.network.policy.NetworkPolicySpecBridge
            .toSessionPolicy(spec)
        assertEquals(
            com.elysium.vanguard.core.runtime.network.policy.NetworkMode.OUTBOUND_ONLY,
            policy.mode
        )
        assertTrue(policy.allowedRemoteHosts.contains("api.example.com"))
    }
}
