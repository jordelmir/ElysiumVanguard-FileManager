package com.elysium.vanguard.core.runtime.clipboard

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files

class ClipboardBrokerImplTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("elysium-clipboard-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun createBroker(): ClipboardBrokerImpl {
        val context: android.content.Context = org.mockito.kotlin.mock()
        whenever(context.filesDir).thenReturn(tempDir)
        return ClipboardBrokerImpl(context)
    }

    @Test
    fun `initial state is Idle`() = block {
        val broker = createBroker()
        assertEquals(ClipboardBrokerState.Idle, broker.state.value)
    }

    @Test
    fun `initial policy is TEXT_ONLY`() = block {
        val broker = createBroker()
        assertEquals(ClipboardPolicy.TEXT_ONLY, broker.policy.value)
    }

    @Test
    fun `initial last access is null`() = block {
        val broker = createBroker()
        assertNull(broker.lastAccess.value)
    }

    @Test
    fun `setPolicy transitions to Active`() = block {
        val broker = createBroker()
        val result = broker.setPolicy("session-1", ClipboardPolicy.FULL)
        assertTrue(result.isSuccess)
        assertEquals(ClipboardBrokerState.Active, broker.state.value)
    }

    @Test
    fun `setPolicy with blank sessionId fails`() = block {
        val broker = createBroker()
        val result = broker.setPolicy("", ClipboardPolicy.FULL)
        assertTrue(result.isFailure)
    }

    @Test
    fun `pushText with DISABLED policy fails`() = block {
        val broker = createBroker()
        broker.setPolicy("session-1", ClipboardPolicy.DISABLED)
        val result = broker.pushText("session-1", "hello")
        assertTrue(result.isFailure)
    }

    @Test
    fun `pushText with TEXT_ONLY policy succeeds`() = block {
        val broker = createBroker()
        broker.setPolicy("session-1", ClipboardPolicy.TEXT_ONLY)
        val result = broker.pushText("session-1", "hello")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `pushText with oversized data fails`() = block {
        val broker = createBroker()
        broker.setPolicy("session-1", ClipboardPolicy.FULL)
        val oversized = "x".repeat(2_000_000)
        val result = broker.pushText("session-1", oversized)
        assertTrue(result.isFailure)
    }

    @Test
    fun `pullText with DISABLED policy fails`() = block {
        val broker = createBroker()
        broker.setPolicy("session-1", ClipboardPolicy.DISABLED)
        val result = broker.pullText("session-1")
        assertTrue(result.isFailure)
    }

    @Test
    fun `pullText with TEXT_ONLY policy returns null when clipboard empty`() = block {
        val broker = createBroker()
        broker.setPolicy("session-1", ClipboardPolicy.TEXT_ONLY)
        val result = broker.pullText("session-1")
        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
    }

    @Test
    fun `pushImage with TEXT_ONLY policy fails`() = block {
        val broker = createBroker()
        broker.setPolicy("session-1", ClipboardPolicy.TEXT_ONLY)
        val result = broker.pushImage("session-1", byteArrayOf(1, 2, 3), "image/png")
        assertTrue(result.isFailure)
    }

    @Test
    fun `pushImage with TEXT_AND_IMAGE policy succeeds`() = block {
        val broker = createBroker()
        broker.setPolicy("session-1", ClipboardPolicy.TEXT_AND_IMAGE)
        val result = broker.pushImage("session-1", byteArrayOf(1, 2, 3), "image/png")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `pushImage with oversized data fails`() = block {
        val broker = createBroker()
        broker.setPolicy("session-1", ClipboardPolicy.FULL)
        val oversized = ByteArray(20_000_000)
        val result = broker.pushImage("session-1", oversized, "image/png")
        assertTrue(result.isFailure)
    }

    @Test
    fun `pullImage with TEXT_ONLY policy fails`() = block {
        val broker = createBroker()
        broker.setPolicy("session-1", ClipboardPolicy.TEXT_ONLY)
        val result = broker.pullImage("session-1")
        assertTrue(result.isFailure)
    }

    @Test
    fun `pullImage with TEXT_AND_IMAGE policy returns null when clipboard empty`() = block {
        val broker = createBroker()
        broker.setPolicy("session-1", ClipboardPolicy.TEXT_AND_IMAGE)
        val result = broker.pullImage("session-1")
        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
    }

    @Test
    fun `two sessions can have independent policies`() = block {
        val broker = createBroker()
        broker.setPolicy("session-1", ClipboardPolicy.DISABLED)
        broker.setPolicy("session-2", ClipboardPolicy.FULL)
        val r1 = broker.pushText("session-1", "hello")
        assertTrue(r1.isFailure)
        val r2 = broker.pushText("session-2", "hello")
        assertTrue(r2.isSuccess)
    }

    @Test
    fun `clear removes session policy`() = block {
        val broker = createBroker()
        broker.setPolicy("session-1", ClipboardPolicy.FULL)
        broker.clear("session-1")
        val result = broker.setPolicy("session-1", ClipboardPolicy.DISABLED)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `last access event recorded after pushText`() = block {
        val broker = createBroker()
        broker.setPolicy("session-1", ClipboardPolicy.TEXT_ONLY)
        broker.pushText("session-1", "hello")
        val event = broker.lastAccess.value
        assertNotNull(event)
        assertEquals("session-1", event!!.sessionId)
        assertEquals(Direction.PUSH, event.direction)
        assertEquals(ClipboardType.TEXT, event.type)
        assertTrue(event.sizeBytes > 0)
    }

    @Test
    fun `close transitions state to Idle`() = block {
        val broker = createBroker()
        broker.setPolicy("session-1", ClipboardPolicy.FULL)
        broker.close()
        assertEquals(ClipboardBrokerState.Idle, broker.state.value)
    }

    @Test
    fun `pushImage persists bytes and pullImage returns them`() = block {
        val broker = createBroker()
        broker.setPolicy("session-1", ClipboardPolicy.FULL)
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val push = broker.pushImage("session-1", bytes, "image/png")
        assertTrue("pushImage should succeed: $push", push.isSuccess)
        val pull = broker.pullImage("session-1")
        assertTrue("pullImage should succeed: $pull", pull.isSuccess)
        val image = pull.getOrNull()
        assertNotNull("image should not be null after push", image)
        assertEquals("image/png", image!!.mimeType)
        assertArrayEquals(bytes, image.data)
    }

    @Test
    fun `clear removes persisted image directory`() = block {
        val broker = createBroker()
        broker.setPolicy("session-1", ClipboardPolicy.FULL)
        broker.pushImage("session-1", byteArrayOf(1, 2, 3, 4), "image/png")
        val before = File(tempDir, "clipboard/session-1")
        assertTrue("image dir should exist after push: $before", before.isDirectory)
        broker.clear("session-1")
        assertFalse("image dir should be gone after clear", before.exists())
    }

    private fun block(block: suspend () -> Unit) = runBlocking { block() }
}
