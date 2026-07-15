package com.elysium.vanguard.core.runtime.network.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Phase 13 — network broker unit tests.
 *
 * The broker is a pure decision engine: it takes a [NetworkPolicy]
 * and a request (outbound or listen) and returns allow / deny /
 * require-confirmation. No I/O happens in the broker, so the
 * tests can exercise every policy mode + every interesting
 * address on the JVM.
 *
 * Properties we pin (each backed by master order §10.2):
 *
 *   - BLOCKED denies everything, including loopback.
 *   - LOOPBACK_ONLY permits only 127.0.0.0/8 and ::1.
 *   - OUTBOUND_ONLY denies non-routable inbound.
 *   - LAN permits RFC1918 + link-local + loopback, denies
 *     public internet.
 *   - INTERNET permits everything, but 0.0.0.0 listen still
 *     requires confirmation unless the policy has
 *     [NetworkPolicy.allowWildcardListen].
 *   - 0.0.0.0 listen ALWAYS returns AllowWithConfirmation
 *     unless the user has explicitly opted in.
 *   - The publish list gates listen on the configured ports.
 *   - The audit log records every successful / confirmation
 *     decision (Deny decisions don't reach the audit log
 *     because the broker returns them before the log).
 */
class NetworkBrokerTest {

    private val loopback = InetAddress.getByName("127.0.0.1")
    private val lanHost = InetAddress.getByName("192.168.1.10")
    private val publicHost = InetAddress.getByName("8.8.8.8")
    private val wildcard = InetAddress.getByName("0.0.0.0")
    private val anyLocal = InetAddress.getByName("::")

    @Test
    fun `BLOCKED denies every outbound`() {
        val policy = NetworkPolicy(mode = NetworkMode.BLOCKED)
        for (target in listOf(loopback, lanHost, publicHost)) {
            val decision = NetworkBroker().decideOutbound(policy, target, 80)
            assertFalse(
                "BLOCKED must deny $target, got $decision",
                decision.permits
            )
        }
    }

    @Test
    fun `BLOCKED denies every listen`() {
        val policy = NetworkPolicy(mode = NetworkMode.BLOCKED)
        val decision = NetworkBroker().decideListen(policy, loopback, 80)
        assertFalse(decision.permits)
    }

    @Test
    fun `LOOPBACK_ONLY permits 127 0 0 1 outbound and denies the internet`() {
        val policy = NetworkPolicy(mode = NetworkMode.LOOPBACK_ONLY)
        val broker = NetworkBroker()
        assertTrue(
            broker.decideOutbound(policy, loopback, 80).permits
        )
        assertFalse(
            broker.decideOutbound(policy, publicHost, 80).permits
        )
    }

    @Test
    fun `LOOPBACK_ONLY permits listen only on loopback`() {
        val policy = NetworkPolicy(mode = NetworkMode.LOOPBACK_ONLY)
        val broker = NetworkBroker()
        assertTrue(
            broker.decideListen(policy, loopback, 80).permits
        )
        assertFalse(
            "loopback-only must not allow LAN listen",
            broker.decideListen(policy, lanHost, 80).permits
        )
    }

    @Test
    fun `OUTBOUND_ONLY permits outbound but not listen`() {
        val policy = NetworkPolicy(mode = NetworkMode.OUTBOUND_ONLY)
        val broker = NetworkBroker()
        assertTrue(
            broker.decideOutbound(policy, publicHost, 443).permits
        )
        assertTrue(
            broker.decideOutbound(policy, lanHost, 22).permits
        )
        assertFalse(
            "OUTBOUND_ONLY must deny a non-published-port listen",
            broker.decideListen(policy, lanHost, 80).permits
        )
    }

    @Test
    fun `OUTBOUND_ONLY permits listen on a published port`() {
        val policy = NetworkPolicy(
            mode = NetworkMode.OUTBOUND_ONLY,
            publishedPorts = setOf(8080)
        )
        val decision = NetworkBroker().decideListen(policy, lanHost, 8080)
        assertTrue(
            "published-port listen must be allowed under OUTBOUND_ONLY",
            decision.permits
        )
    }

    @Test
    fun `OUTBOUND_ONLY denies a listen on a non-published port`() {
        val policy = NetworkPolicy(
            mode = NetworkMode.OUTBOUND_ONLY,
            publishedPorts = setOf(8080)
        )
        val decision = NetworkBroker().decideListen(policy, lanHost, 9090)
        assertFalse(decision.permits)
    }

    @Test
    fun `LAN permits RFC1918 and denies public internet`() {
        val policy = NetworkPolicy(mode = NetworkMode.LAN)
        val broker = NetworkBroker()
        assertTrue(broker.decideOutbound(policy, lanHost, 80).permits)
        assertTrue(broker.decideOutbound(policy, loopback, 80).permits)
        assertFalse(
            "LAN policy must deny 8.8.8.8",
            broker.decideOutbound(policy, publicHost, 80).permits
        )
    }

    @Test
    fun `INTERNET permits public hosts and 192 168 but denies 0 0 0 0 listen without consent`() {
        val policy = NetworkPolicy(mode = NetworkMode.INTERNET)
        val broker = NetworkBroker()
        assertTrue(broker.decideOutbound(policy, publicHost, 80).permits)
        assertTrue(broker.decideListen(policy, lanHost, 80).permits)
        val decision = broker.decideListen(policy, wildcard, 80)
        assertTrue(
            "0.0.0.0 listen must require confirmation under INTERNET",
            decision is NetworkDecision.AllowWithConfirmation
        )
    }

    @Test
    fun `0 0 0 0 listen is allowed when the policy opts in`() {
        val policy = NetworkPolicy(
            mode = NetworkMode.INTERNET,
            allowWildcardListen = true
        )
        val decision = NetworkBroker().decideListen(policy, wildcard, 80)
        assertTrue(
            "0.0.0.0 listen with consent must be allowed",
            decision is NetworkDecision.Allow
        )
    }

    @Test
    fun `OUTBOUND LAN and INTERNET require confirmation for 0 0 0 0 listen`() {
        // BLOCKED hard-denies (no confirm path).
        // LOOPBACK_ONLY also hard-denies a 0.0.0.0 bind — there is
        // no confirm path out of a "loopback only" policy, the
        // bind is simply not allowed.
        for (mode in listOf(NetworkMode.OUTBOUND_ONLY, NetworkMode.LAN, NetworkMode.INTERNET)) {
            val policy = NetworkPolicy(mode = mode, allowWildcardListen = false)
            val decision = NetworkBroker().decideListen(policy, wildcard, 80)
            assertTrue(
                "$mode must require confirmation for 0.0.0.0 listen, got $decision",
                decision is NetworkDecision.AllowWithConfirmation
            )
        }
    }

    @Test
    fun `BLOCKED mode denies a 0 0 0 0 listen without confirmation`() {
        // BLOCKED is the only mode that hard-denies 0.0.0.0.
        // The user cannot "confirm" their way out of a BLOCKED
        // policy.
        val policy = NetworkPolicy(mode = NetworkMode.BLOCKED)
        val decision = NetworkBroker().decideListen(policy, wildcard, 80)
        assertTrue(decision is NetworkDecision.Deny)
    }

    @Test
    fun `allow-list host entry permits matching IP literal`() {
        val policy = NetworkPolicy(
            mode = NetworkMode.INTERNET,
            allowedRemoteHosts = setOf("8.8.8.8")
        )
        val decision = NetworkBroker().decideOutbound(policy, publicHost, 443)
        assertTrue(
            "allow-listed IP must be reachable",
            decision.permits
        )
    }

    @Test
    fun `allow-list host entry denies a non-matching destination`() {
        val policy = NetworkPolicy(
            mode = NetworkMode.INTERNET,
            allowedRemoteHosts = setOf("github.com")
        )
        val decision = NetworkBroker().decideOutbound(policy, publicHost, 443)
        assertFalse(decision.permits)
    }

    @Test
    fun `isLan recognises RFC1918 ranges`() {
        assertTrue(NetworkBroker.isLan(InetAddress.getByName("10.0.0.1")))
        assertTrue(NetworkBroker.isLan(InetAddress.getByName("172.16.0.1")))
        assertTrue(NetworkBroker.isLan(InetAddress.getByName("172.31.255.254")))
        assertTrue(NetworkBroker.isLan(InetAddress.getByName("192.168.0.1")))
        assertTrue(NetworkBroker.isLan(InetAddress.getByName("169.254.1.1")))
        assertTrue(NetworkBroker.isLan(InetAddress.getByName("100.64.0.1")))
    }

    @Test
    fun `isLan rejects public addresses`() {
        assertFalse(NetworkBroker.isLan(InetAddress.getByName("8.8.8.8")))
        assertFalse(NetworkBroker.isLan(InetAddress.getByName("172.32.0.1")))
        assertFalse(NetworkBroker.isLan(InetAddress.getByName("192.169.0.1")))
    }

    @Test
    fun `isLan handles IPv6 unique-local and link-local`() {
        assertTrue(NetworkBroker.isLan(InetAddress.getByName("fc00::1")))
        assertTrue(NetworkBroker.isLan(InetAddress.getByName("fd00::1")))
        assertTrue(NetworkBroker.isLan(InetAddress.getByName("fe80::1")))
    }

    @Test
    fun `policy init rejects invalid port numbers`() {
        try {
            NetworkPolicy(
                mode = NetworkMode.OUTBOUND_ONLY,
                publishedPorts = setOf(0, 80, 70000)
            )
            assert(false) { "expected IllegalArgumentException" }
        } catch (expected: IllegalArgumentException) {
            // 0 and 70000 are out of the valid TCP/UDP range.
        }
    }

    @Test
    fun `policy init rejects blank host entries`() {
        try {
            NetworkPolicy(
                mode = NetworkMode.INTERNET,
                allowedRemoteHosts = setOf("github.com", "  ")
            )
            assert(false) { "expected IllegalArgumentException" }
        } catch (expected: IllegalArgumentException) {
            // Blank entries in the allow-list are a configuration bug.
        }
    }

    @Test
    fun `audit log records successful outbound and listen decisions`() {
        val log = NetworkAuditLog()
        val policy = NetworkPolicy(mode = NetworkMode.INTERNET)
        val broker = NetworkBroker()
        broker.decideOutbound(policy, publicHost, 443, audit = log)
        broker.decideListen(policy, lanHost, 8080, audit = log)
        assertEquals(2, log.size())
        val events = log.snapshot()
        assertEquals(AuditEvent.Kind.OUTBOUND, events[0].kind)
        assertEquals(publicHost, events[0].target)
        assertEquals(AuditEvent.Kind.LISTEN, events[1].kind)
        assertEquals(lanHost, events[1].target)
    }

    @Test
    fun `audit log records 0 0 0 0 listen as AllowWithConfirmation`() {
        val log = NetworkAuditLog()
        val policy = NetworkPolicy(mode = NetworkMode.INTERNET)
        val broker = NetworkBroker()
        broker.decideListen(policy, wildcard, 80, audit = log)
        assertEquals(1, log.size())
        assertTrue(
            log.snapshot().first().decision is NetworkDecision.AllowWithConfirmation
        )
    }

    @Test
    fun `audit log is thread-safe under concurrent record`() {
        val log = NetworkAuditLog()
        val threads = (0 until 8).map {
            Thread {
                repeat(100) {
                    log.record(
                        AuditEvent(
                            policy = NetworkPolicy(mode = NetworkMode.INTERNET),
                            kind = AuditEvent.Kind.OUTBOUND,
                            target = publicHost,
                            port = 80,
                            decision = NetworkDecision.Allow
                        )
                    )
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }
        assertEquals(800, log.size())
    }
}
