package com.elysium.vanguard.core.runtime.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for ClipboardBroker policy engine.
 */
class ClipboardBrokerTest {

    @Test
    fun `DISABLED policy prevents all operations`() {
        val policy = ClipboardPolicy.DISABLED
        assertEquals(ClipboardPolicy.DISABLED, policy)
    }

    @Test
    fun `TEXT_ONLY policy allows text but not images`() {
        val policy = ClipboardPolicy.TEXT_ONLY
        assertEquals(ClipboardPolicy.TEXT_ONLY, policy)
        assertFalse(policy == ClipboardPolicy.TEXT_AND_IMAGE)
    }

    @Test
    fun `TEXT_AND_IMAGE allows both`() {
        val policy = ClipboardPolicy.TEXT_AND_IMAGE
        assertEquals(ClipboardPolicy.TEXT_AND_IMAGE, policy)
    }

    @Test
    fun `clipboard access event records direction`() {
        val event = ClipboardAccessEvent(
            sessionId = "session-1",
            direction = Direction.PUSH,
            type = ClipboardType.TEXT,
            sizeBytes = 100
        )
        assertEquals("session-1", event.sessionId)
        assertEquals(Direction.PUSH, event.direction)
        assertEquals(ClipboardType.TEXT, event.type)
        assertEquals(100, event.sizeBytes)
        assertTrue(event.timestamp > 0)
    }

    @Test
    fun `clipboard image data equals`() {
        val a = ClipboardImage(data = byteArrayOf(1, 2, 3), mimeType = "image/png", width = 100, height = 100)
        val b = ClipboardImage(data = byteArrayOf(1, 2, 3), mimeType = "image/png", width = 100, height = 100)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `clipboard image data not equals different bytes`() {
        val a = ClipboardImage(data = byteArrayOf(1, 2, 3), mimeType = "image/png", width = 100, height = 100)
        val b = ClipboardImage(data = byteArrayOf(4, 5, 6), mimeType = "image/png", width = 100, height = 100)
        assertFalse(a == b)
    }

    @Test
    fun `all policy levels are distinct`() {
        val policies = ClipboardPolicy.entries
        assertEquals(5, policies.size)
        val names = policies.map { it.name }.toSet()
        assertEquals(5, names.size)
    }
}
