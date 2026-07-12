package com.elysium.vanguard.core.runtime.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestDnsConfigTest {
    @Test
    fun `resolver config renders only structured DNS directives`() {
        val config = GuestDnsConfig(
            nameservers = listOf("192.0.2.1", "2001:db8::1"),
            searchDomains = listOf("corp.example", "lab.example")
        )
        val text = config.renderResolvConf()
        assertTrue(text.contains("search corp.example lab.example"))
        assertTrue(text.contains("nameserver 192.0.2.1"))
        assertTrue(text.contains("nameserver 2001:db8::1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `resolver config rejects directive injection`() {
        GuestDnsConfig(nameservers = listOf("1.1.1.1\nnameserver 8.8.8.8"))
    }

    @Test
    fun `empty resolver config stays intentionally empty`() {
        assertEquals(GuestDnsConfig.EMPTY, GuestDnsConfigProvider.NONE.current())
    }
}
