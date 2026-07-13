package com.elysium.vanguard.core.runtime.distros.gui.rfb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class RfbClientTest {

    @Test
    fun `negotiates localhost RFB raw framebuffer and sends input`() {
        ServerSocket(0).use { serverSocket ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val server = executor.submit<Unit> {
                    serverSocket.accept().use { socket ->
                        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                        output.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
                        output.flush()
                        assertArrayEquals("RFB 003.008\n".toByteArray(Charsets.US_ASCII), ByteArray(12).also(input::readFully))
                        output.writeByte(1)
                        output.writeByte(1)
                        output.flush()
                        assertEquals(1, input.readUnsignedByte())
                        output.writeInt(0)
                        output.flush()
                        assertEquals(1, input.readUnsignedByte()) // ClientInit / shared desktop
                        writeServerInit(output, width = 2, height = 1, name = "Elysium test desktop")

                        assertEquals(0, input.readUnsignedByte())
                        input.skipFully(3)
                        assertEquals(32, input.readUnsignedByte())
                        assertEquals(24, input.readUnsignedByte())
                        input.skipFully(14)

                        assertEquals(2, input.readUnsignedByte())
                        input.skipFully(1)
                        assertEquals(2, input.readUnsignedShort())
                        assertEquals(0, input.readInt())
                        assertEquals(-223, input.readInt())

                        assertEquals(3, input.readUnsignedByte())
                        assertEquals(0, input.readUnsignedByte())
                        assertEquals(0, input.readUnsignedShort())
                        assertEquals(0, input.readUnsignedShort())
                        assertEquals(2, input.readUnsignedShort())
                        assertEquals(1, input.readUnsignedShort())
                        writeRawFramebuffer(output, width = 2, height = 1)

                        assertEquals(5, input.readUnsignedByte())
                        assertEquals(1, input.readUnsignedByte())
                        assertEquals(0, input.readUnsignedShort())
                        assertEquals(0, input.readUnsignedShort())

                        assertEquals(4, input.readUnsignedByte())
                        assertEquals(1, input.readUnsignedByte())
                        assertEquals(0, input.readUnsignedShort())
                        assertEquals(0xFF0D, input.readInt())
                    }
                }

                var clientFailure: Throwable? = null
                try {
                    RfbClient.connect(port = serverSocket.localPort).use { client ->
                        assertEquals("Elysium test desktop", client.server.desktopName)
                        client.requestFramebufferUpdate(incremental = false)
                        val frame = client.readFrame() ?: error("expected framebuffer update")
                        assertEquals(2, frame.width)
                        assertEquals(1, frame.height)
                        assertArrayEquals(intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()), frame.argb)
                        client.sendPointer(x = -4, y = 99, buttonMask = 1)
                        client.sendKey(keysym = 0xFF0D, down = true)
                    }
                } catch (failure: Throwable) {
                    clientFailure = failure
                }
                try {
                    server.get(5, TimeUnit.SECONDS)
                } catch (failure: ExecutionException) {
                    if (clientFailure != null) clientFailure.addSuppressed(failure.cause ?: failure)
                    else throw failure
                }
                clientFailure?.let { throw it }
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private fun writeServerInit(output: DataOutputStream, width: Int, height: Int, name: String) {
        output.writeShort(width)
        output.writeShort(height)
        // 32bpp, 24 depth, little-endian, true colour, B/G/R byte layout.
        output.writeByte(32)
        output.writeByte(24)
        output.writeByte(0)
        output.writeByte(1)
        output.writeShort(255)
        output.writeShort(255)
        output.writeShort(255)
        output.writeByte(16)
        output.writeByte(8)
        output.writeByte(0)
        output.write(ByteArray(3))
        val bytes = name.toByteArray(Charsets.UTF_8)
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    private fun writeRawFramebuffer(output: DataOutputStream, width: Int, height: Int) {
        output.writeByte(0) // FramebufferUpdate
        output.writeByte(0)
        output.writeShort(1)
        output.writeShort(0)
        output.writeShort(0)
        output.writeShort(width)
        output.writeShort(height)
        output.writeInt(0) // RAW
        output.write(byteArrayOf(
            0, 0, 0xFF.toByte(), 0, // red in B,G,R,pad order
            0, 0xFF.toByte(), 0, 0  // green
        ))
        output.flush()
    }

    private fun DataInputStream.skipFully(count: Int) {
        var remaining = count
        while (remaining > 0) {
            val skipped = skipBytes(remaining)
            if (skipped <= 0) error("unexpected EOF")
            remaining -= skipped
        }
    }
}
