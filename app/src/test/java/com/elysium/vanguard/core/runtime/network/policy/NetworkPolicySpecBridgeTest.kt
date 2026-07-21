package com.elysium.vanguard.core.runtime.network.policy

import com.elysium.vanguard.core.runtime.workspace_def.NetworkAccessMode
import com.elysium.vanguard.core.runtime.workspace_def.NetworkPolicySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 105 — JVM tests for [NetworkPolicySpecBridge].
 *
 * The bridge is the **only** place a workspace's
 * [NetworkPolicySpec] is translated to a session
 * [NetworkPolicy]. The truth table is pinned by
 * these tests so a future refactor cannot silently
 * widen the network access of a workspace.
 */
class NetworkPolicySpecBridgeTest {

    @Test
    fun `DENY_ALL maps to LOOPBACK_ONLY (not BLOCKED) with empty allow-lists`() {
        // LOOPBACK_ONLY is preferred over BLOCKED
        // because dropping loopback breaks a lot of
        // local IPC (X11 forwarding, dbus, systemd-resolved).
        val policy = NetworkPolicySpecBridge.toSessionPolicy(
            NetworkPolicySpec.DEFAULT
        )
        assertEquals(NetworkMode.LOOPBACK_ONLY, policy.mode)
        assertTrue("DENY_ALL must have empty allowedRemoteHosts",
            policy.allowedRemoteHosts.isEmpty())
        assertTrue("DENY_ALL must have empty publishedPorts",
            policy.publishedPorts.isEmpty())
        assertFalse("DENY_ALL must not allow wildcard listen",
            policy.allowWildcardListen)
    }

    @Test
    fun `ALLOW_LIST maps to OUTBOUND_ONLY with hosts and ports propagated`() {
        val spec = NetworkPolicySpec(
            mode = NetworkAccessMode.ALLOW_LIST,
            allowedHosts = listOf("api.example.com", "*.cdn.example.com"),
            allowedPorts = setOf(443, 8443),
            dnsAllowed = true,
        )
        val policy = NetworkPolicySpecBridge.toSessionPolicy(spec)
        assertEquals(NetworkMode.OUTBOUND_ONLY, policy.mode)
        assertEquals(
            setOf("api.example.com", "*.cdn.example.com"),
            policy.allowedRemoteHosts
        )
        assertEquals(setOf(443, 8443), policy.publishedPorts)
        assertFalse(policy.allowWildcardListen)
    }

    @Test
    fun `ALLOW_ALL maps to INTERNET with empty allowedRemoteHosts (any host)`() {
        val spec = NetworkPolicySpec.OPEN
        val policy = NetworkPolicySpecBridge.toSessionPolicy(spec)
        assertEquals(NetworkMode.INTERNET, policy.mode)
        assertTrue(
            "ALLOW_ALL must have empty allowedRemoteHosts (INTERNET mode = any host)",
            policy.allowedRemoteHosts.isEmpty()
        )
        assertFalse(policy.allowWildcardListen)
    }

    @Test
    fun `ALLOW_ALL propagates the explicit allowedPorts as publishedPorts`() {
        // A workspace that wants "INTERNET but only listen
        // on port 8080" still uses INTERNET mode but
        // declares the listen port.
        val spec = NetworkPolicySpec(
            mode = NetworkAccessMode.ALLOW_ALL,
            allowedPorts = setOf(8080),
        )
        val policy = NetworkPolicySpecBridge.toSessionPolicy(spec)
        assertEquals(NetworkMode.INTERNET, policy.mode)
        assertEquals(setOf(8080), policy.publishedPorts)
    }

    @Test
    fun `ALLOW_LIST with an empty allowedHosts is rejected by the spec init block (precondition)`() {
        // This is a regression test: the spec's init
        // block rejects ALLOW_LIST + empty hosts. The
        // bridge just trusts the spec's invariants; if
        // the spec is invalid, the bridge never runs.
        // We verify the invariant by trying to construct
        // the spec — it must throw.
        try {
            NetworkPolicySpec(
                mode = NetworkAccessMode.ALLOW_LIST,
                allowedHosts = emptyList(),
            )
            org.junit.Assert.fail("expected IllegalArgumentException for ALLOW_LIST + empty allowedHosts")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `bridge is the only place a workspace spec reaches the firewall (sanity test)`() {
        // The bridge produces a NetworkPolicy that the
        // firewall can consume. The firewall's own
        // invariants (the NetworkPolicy's init block)
        // must pass on the bridged output.
        val specs = listOf(
            NetworkPolicySpec.DEFAULT,
            NetworkPolicySpec.OPEN,
            NetworkPolicySpec(
                mode = NetworkAccessMode.ALLOW_LIST,
                allowedHosts = listOf("api.example.com"),
                allowedPorts = setOf(443),
            ),
        )
        for (spec in specs) {
            // No throw = the NetworkPolicy constructor
            // ran successfully; the spec bridged
            // cleanly into a valid session policy.
            NetworkPolicySpecBridge.toSessionPolicy(spec)
        }
    }

    // ====================================================================
    // isDenyByDefault
    // ====================================================================

    @Test
    fun `isDenyByDefault is true only for DENY_ALL`() {
        assertTrue(
            "DEFAULT (DENY_ALL) must be deny-by-default",
            NetworkPolicySpecBridge.isDenyByDefault(NetworkPolicySpec.DEFAULT)
        )
        assertFalse(
            "ALLOW_LIST must NOT be deny-by-default",
            NetworkPolicySpecBridge.isDenyByDefault(NetworkPolicySpec(
                mode = NetworkAccessMode.ALLOW_LIST,
                allowedHosts = listOf("api.example.com"),
            ))
        )
        assertFalse(
            "ALLOW_ALL must NOT be deny-by-default",
            NetworkPolicySpecBridge.isDenyByDefault(NetworkPolicySpec.OPEN)
        )
    }

    @Test
    fun `isDenyByDefault is the audit-friendly predicate the security log uses`() {
        // The security audit log records whether each
        // workspace session ran with the platform's
        // safe-default posture. Pin the truth table.
        assertTrue(NetworkPolicySpecBridge.isDenyByDefault(NetworkPolicySpec.DEFAULT))
    }
}
