package com.elysium.vanguard.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentGatewayEndpointPolicyTest {
    @Test
    fun `accepts localhost http for adb reverse development`() {
        assertEquals(
            "http://localhost:8787",
            AgentGatewayEndpointPolicy.normalize("http://localhost:8787/")
        )
    }

    @Test
    fun `accepts secure remote endpoint`() {
        assertEquals(
            "https://core.example.com",
            AgentGatewayEndpointPolicy.normalize("https://core.example.com/")
        )
    }

    @Test
    fun `rejects cleartext lan endpoint`() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentGatewayEndpointPolicy.normalize("http://192.168.1.14:8787")
        }
    }

    @Test
    fun `rejects URLs containing embedded credentials`() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentGatewayEndpointPolicy.normalize("https://user:password@core.example.com")
        }
    }
}
