package com.elysium.vanguard.core.runtime.distros.gui.rfb

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RfbSessionTest {

    @Test
    fun `session streams local frames forwards input and closes deterministically`() = runBlocking {
        val connection = FakeConnection()
        val session = RfbSession(RfbSession.Config(port = 5901)) { connection }
        session.start()
        withTimeout(1_000) { session.state.first { it is RfbSession.State.Connected } }
        connection.frames.offer(Result.success(RfbFrame(2, 1, intArrayOf(0xFF00FF00.toInt(), 0xFF0000FF.toInt()))))

        val frame = withTimeout(1_000) { session.frames.filterNotNull().first() }
        assertEquals(2, frame.width)
        assertTrue(session.state.value is RfbSession.State.Streaming)

        session.sendPointer(1, 0, 1)
        session.sendKey(0xFF0D, true)
        withTimeout(1_000) {
            while (synchronized(connection.inputEvents) { connection.inputEvents.size } < 2) delay(5)
        }
        assertEquals(
            listOf("pointer:1,0,1", "key:65293,true"),
            synchronized(connection.inputEvents) { connection.inputEvents.toList() }
        )

        session.stop()
        assertEquals(RfbSession.State.Stopped, session.state.value)
        assertTrue(connection.closed)
    }

    private class FakeConnection : RfbConnection {
        override val server = RfbServerInfo(2, 1, "fake", 8)
        val frames = LinkedBlockingQueue<Result<RfbFrame?>>()
        val inputEvents = mutableListOf<String>()
        @Volatile var closed = false

        override fun requestFramebufferUpdate(incremental: Boolean) = Unit

        override fun readFrame(): RfbFrame? {
            val result = frames.poll(2, TimeUnit.SECONDS) ?: throw IOException("fake RFB frame timeout")
            return result.getOrElse { throw it }
        }

        override fun sendPointer(x: Int, y: Int, buttonMask: Int) {
            synchronized(inputEvents) { inputEvents += "pointer:$x,$y,$buttonMask" }
        }

        override fun sendKey(keysym: Int, down: Boolean) {
            synchronized(inputEvents) { inputEvents += "key:$keysym,$down" }
        }

        override fun close() {
            closed = true
            frames.offer(Result.failure(IOException("closed")))
        }
    }
}
