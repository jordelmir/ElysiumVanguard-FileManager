package com.elysium.vanguard.core.runtime.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for NetworkBroker policy engine.
 */
class NetworkBrokerTest {

    @Test
    fun `policy level LOOPBACK only allows loopback`() {
        val policy = NetworkPolicy(level = PolicyLevel.LOOPBACK, allowDns = false)
        assertTrue(policy.allowLoopback)
        assertFalse(policy.allowDns)
    }

    @Test
    fun `policy level BLOCKED blocks everything`() {
        val policy = NetworkPolicy(level = PolicyLevel.BLOCKED, allowDns = false, allowLoopback = false)
        assertFalse(policy.allowDns)
        assertFalse(policy.allowLoopback)
    }

    @Test
    fun `policy level INTERNET allows DNS`() {
        val policy = NetworkPolicy(level = PolicyLevel.INTERNET)
        assertTrue(policy.allowDns)
        assertTrue(policy.allowLoopback)
    }

    @Test
    fun `policy validates max connections`() {
        try {
            NetworkPolicy(level = PolicyLevel.INTERNET, maxConnections = 0)
            assertTrue(false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("maxConnections") == true)
        }
    }

    @Test
    fun `port binding validates guest port range`() {
        try {
            PortBinding(guestPort = 0)
            assertTrue(false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("guest port") == true)
        }
    }

    @Test
    fun `port binding validates host port range`() {
        try {
            PortBinding(guestPort = 8080, hostPort = 70000)
            assertTrue(false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("host port") == true)
        }
    }

    @Test
    fun `port binding with valid ports`() {
        val binding = PortBinding(guestPort = 8080, hostPort = 8080, description = "web server")
        assertEquals(8080, binding.guestPort)
        assertEquals(8080, binding.hostPort)
        assertEquals(Protocol.TCP, binding.protocol)
    }

    @Test
    fun `custom policy with allowed hosts`() {
        val policy = NetworkPolicy(
            level = PolicyLevel.CUSTOM,
            allowedHosts = setOf("github.com", "crates.io"),
            allowedPorts = setOf(443, 80)
        )
        assertEquals(2, policy.allowedHosts.size)
        assertEquals(2, policy.allowedPorts.size)
    }

    @Test
    fun `policy with rate limiting`() {
        val policy = NetworkPolicy(
            level = PolicyLevel.INTERNET,
            maxBytesPerSecond = 1024 * 1024
        )
        assertEquals(1024L * 1024L, policy.maxBytesPerSecond)
    }
}
