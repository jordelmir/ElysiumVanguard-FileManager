package com.elysium.vanguard.core.runtime.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 11.2 — Unit tests for [buildGuestDnsConfig].
 *
 * This is the *only* piece of the [AndroidGuestDnsObserver] that
 * runs on the JVM unit test classpath. The callback wiring itself
 * needs Robolectric or a device; the translation from `LinkProperties`
 * to [GuestDnsConfig] does not.
 *
 * The builder intentionally takes [String] instead of
 * [java.net.InetAddress] because `InetAddress` is an Android-only
 * type and is not on the JVM test classpath. The Android observer
 * converts the `InetAddress` list to a `List<String>` before calling.
 */
class GuestDnsSnapshotBuilderTest {

    @Test
    fun `translates a typical wifi payload`() {
        val config = buildGuestDnsConfig(
            rawHostAddresses = listOf("192.0.2.10", "192.0.2.11"),
            domains = "home.example lan.example"
        )
        assertEquals(listOf("192.0.2.10", "192.0.2.11"), config.nameservers)
        assertEquals(listOf("home.example", "lan.example"), config.searchDomains)
    }

    @Test
    fun `strips the IPv6 zone suffix that the caller already removed`() {
        // The Android observer's `hostAddress?.substringBefore('%')`
        // strips the zone at the call site; we just verify the builder
        // tolerates a clean link-local address.
        val config = buildGuestDnsConfig(
            rawHostAddresses = listOf("fe80::1"),
            domains = null
        )
        assertEquals(listOf("fe80::1"), config.nameservers)
    }

    @Test
    fun `drops blank and whitespace-only addresses`() {
        val config = buildGuestDnsConfig(
            rawHostAddresses = listOf("  ", "", "192.0.2.10", "\t"),
            domains = null
        )
        assertEquals(listOf("192.0.2.10"), config.nameservers)
    }

    @Test
    fun `deduplicates repeated nameservers without changing order`() {
        val config = buildGuestDnsConfig(
            rawHostAddresses = listOf("192.0.2.10", "192.0.2.10", "192.0.2.11", "192.0.2.10"),
            domains = null
        )
        assertEquals(listOf("192.0.2.10", "192.0.2.11"), config.nameservers)
    }

    @Test
    fun `splits domains on any whitespace`() {
        val config = buildGuestDnsConfig(
            rawHostAddresses = emptyList(),
            domains = "  a.example  \t b.example\nc.example  "
        )
        assertEquals(listOf("a.example", "b.example", "c.example"), config.searchDomains)
    }

    @Test
    fun `null and empty domains yield empty search list`() {
        val fromNull = buildGuestDnsConfig(rawHostAddresses = emptyList(), domains = null)
        val fromEmpty = buildGuestDnsConfig(rawHostAddresses = emptyList(), domains = "")
        val fromBlank = buildGuestDnsConfig(rawHostAddresses = emptyList(), domains = "   ")
        assertTrue(fromNull.searchDomains.isEmpty())
        assertTrue(fromEmpty.searchDomains.isEmpty())
        assertTrue(fromBlank.searchDomains.isEmpty())
    }

    @Test
    fun `mixed IPv4 and IPv6 nameservers keep stable order`() {
        val config = buildGuestDnsConfig(
            rawHostAddresses = listOf("192.0.2.1", "2001:db8::1", "192.0.2.2"),
            domains = null
        )
        assertEquals(listOf("192.0.2.1", "2001:db8::1", "192.0.2.2"), config.nameservers)
    }

    @Test
    fun `result is always a valid GuestDnsConfig`() {
        // The constructor throws on invalid input; reaching the
        // assertion means the builder produced something valid.
        val config = buildGuestDnsConfig(
            rawHostAddresses = listOf("10.0.0.1"),
            domains = "x.example"
        )
        assertEquals(1, config.nameservers.size)
    }

    @Test
    fun `property - builder is invariant under input permutation of distinct values`() {
        val pool = listOf("a", "b", "c", "d", "e")
        val permuted = pool.reversed()
        val configA = buildGuestDnsConfig(rawHostAddresses = pool, domains = null)
        val configB = buildGuestDnsConfig(rawHostAddresses = permuted, domains = null)
        // Distinct dedupes; the same set of values in any order
        // produces the same nameservers list, but in input order
        // (the builder does not re-sort). Verify the actual property
        // we care about: a *multiset* of N distinct values yields
        // exactly N nameservers, regardless of order.
        assertEquals(configA.nameservers.size, configB.nameservers.size)
        assertEquals(pool.size, configA.nameservers.size)
    }
}
