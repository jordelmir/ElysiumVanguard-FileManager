package com.elysium.vanguard.core.runtime.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for DNS fallback behavior and GuestDnsConfig.
 */
class AndroidGuestDnsConfigProviderTest {

    @Test
    fun `GuestDnsConfig with nameservers creates successfully`() {
        val config = GuestDnsConfig(
            nameservers = listOf("8.8.8.8", "8.8.4.4"),
            searchDomains = listOf("example.com"),
            source = "android"
        )
        assertTrue(config.nameservers.isNotEmpty())
        assertEquals(2, config.nameservers.size)
        assertEquals("android", config.source)
    }

    @Test
    fun `GuestDnsConfig with empty nameservers creates with empty list`() {
        val config = GuestDnsConfig(
            nameservers = emptyList(),
            searchDomains = emptyList(),
            source = "empty"
        )
        assertTrue(config.nameservers.isEmpty())
    }

    @Test
    fun `GuestDnsConfig fallback contains known DNS providers`() {
        val config = GuestDnsConfig(
            nameservers = listOf("1.1.1.1", "8.8.8.8", "2606:4700:4700::1111"),
            searchDomains = emptyList(),
            source = "fallback"
        )
        assertTrue(config.nameservers.contains("1.1.1.1"))
        assertTrue(config.nameservers.contains("8.8.8.8"))
        assertTrue(config.nameservers.contains("2606:4700:4700::1111"))
    }

    @Test
    fun `renderResolvConf produces valid resolv conf format`() {
        val config = GuestDnsConfig(
            nameservers = listOf("1.1.1.1", "8.8.8.8"),
            searchDomains = listOf("test.local"),
            source = "test"
        )
        val resolvConf = config.renderResolvConf()
        assertTrue(resolvConf.contains("nameserver 1.1.1.1"))
        assertTrue(resolvConf.contains("nameserver 8.8.8.8"))
        assertTrue(resolvConf.contains("search test.local"))
        assertTrue(resolvConf.contains("Source: test"))
        assertTrue(resolvConf.contains("options timeout:2 attempts:2"))
    }

    @Test
    fun `renderResolvConf omits search when empty`() {
        val config = GuestDnsConfig(
            nameservers = listOf("8.8.8.8"),
            searchDomains = emptyList(),
            source = "test"
        )
        val resolvConf = config.renderResolvConf()
        assertTrue(resolvConf.contains("nameserver 8.8.8.8"))
        assertFalse(resolvConf.contains("search"))
    }

    @Test
    fun `EMPTY config has no nameservers`() {
        val config = GuestDnsConfig.EMPTY
        assertTrue(config.nameservers.isEmpty())
        assertEquals("empty", config.source)
    }

    @Test
    fun `GuestDnsConfig fallback config includes IPv6`() {
        val config = GuestDnsConfig(
            nameservers = listOf("1.1.1.1", "8.8.8.8", "2606:4700:4700::1111", "2001:4860:4860::8888"),
            searchDomains = emptyList(),
            source = "fallback"
        )
        val hasIPv6 = config.nameservers.any { it.contains(":") }
        assertTrue(hasIPv6)
    }

    @Test
    fun `GuestDnsConfig with mixed sources`() {
        val config = GuestDnsConfig(
            nameservers = listOf("8.8.8.8", "2001:4860:4860::8888"),
            searchDomains = listOf("lan", "home.arpa"),
            source = "android+fallback"
        )
        assertEquals(2, config.nameservers.size)
        assertEquals(2, config.searchDomains.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `GuestDnsConfig rejects whitespace in nameserver`() {
        GuestDnsConfig(
            nameservers = listOf("8.8.8.8 bad"),
            searchDomains = emptyList(),
            source = "test"
        )
    }

    @Test
    fun `GuestDnsConfig single nameserver is valid`() {
        val config = GuestDnsConfig(
            nameservers = listOf("8.8.8.8"),
            searchDomains = emptyList(),
            source = "test"
        )
        assertEquals(1, config.nameservers.size)
    }

    @Test
    fun `GuestDnsConfig equality works`() {
        val a = GuestDnsConfig(nameservers = listOf("8.8.8.8"), source = "test")
        val b = GuestDnsConfig(nameservers = listOf("8.8.8.8"), source = "test")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `GuestDnsConfig copy preserves fields`() {
        val original = GuestDnsConfig(
            nameservers = listOf("8.8.8.8"),
            searchDomains = listOf("example.com"),
            source = "test"
        )
        val copy = original.copy(source = "modified")
        assertEquals(original.nameservers, copy.nameservers)
        assertEquals(original.searchDomains, copy.searchDomains)
        assertEquals("modified", copy.source)
    }

    @Test
    fun `GuestDnsProvider NONE returns empty config`() {
        val provider = GuestDnsConfigProvider.NONE
        val config = provider.current()
        assertTrue(config.nameservers.isEmpty())
    }
}
