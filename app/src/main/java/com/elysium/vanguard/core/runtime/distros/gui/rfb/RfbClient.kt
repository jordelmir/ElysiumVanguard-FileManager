package com.elysium.vanguard.core.runtime.distros.gui.rfb

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Small, real RFB 3.3/3.7/3.8 client for a localhost Linux VNC server.
 *
 * It deliberately accepts only the RFB "None" security type and requests
 * RAW true-colour rectangles. That keeps the first graphical transport
 * auditable: no fake desktop pixels, no remote exposure, no password
 * persistence, and no opaque native decoder. A caller must run this on an
 * I/O dispatcher, then render [RfbFrame] on Android's graphics surface.
 */
internal class RfbClient private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    override val server: RfbServerInfo
) : RfbConnection {
    private var framebuffer = IntArray(server.width * server.height)
    private val writeLock = Any()

    /** Asks the VNC server for a full or incremental frame. */
    override fun requestFramebufferUpdate(incremental: Boolean) = synchronized(writeLock) {
        output.writeByte(CLIENT_FRAMEBUFFER_UPDATE_REQUEST)
        output.writeByte(if (incremental) 1 else 0)
        output.writeShort(0)
        output.writeShort(0)
        output.writeShort(server.width)
        output.writeShort(server.height)
        output.flush()
    }

    /** Reads one server message; returns a frame only when pixels changed. */
    override fun readFrame(): RfbFrame? {
        return when (input.readUnsignedByte()) {
            SERVER_FRAMEBUFFER_UPDATE -> readFramebufferUpdate()
            SERVER_BELL -> null
            SERVER_CUT_TEXT -> {
                input.skipFully(3)
                input.skipFully(readBoundedLength(MAX_CLIPBOARD_BYTES))
                null
            }
            else -> throw IOException("unsupported RFB server message")
        }
    }

    /** Sends pointer movement/button state in framebuffer coordinates. */
    override fun sendPointer(x: Int, y: Int, buttonMask: Int) = synchronized(writeLock) {
        require(buttonMask in 0..0xFF) { "invalid RFB button mask" }
        output.writeByte(CLIENT_POINTER_EVENT)
        output.writeByte(buttonMask)
        output.writeShort(x.coerceIn(0, server.width - 1))
        output.writeShort(y.coerceIn(0, server.height - 1))
        output.flush()
    }

    /** Sends a keysym, e.g. Unicode or XK_* constants selected by the UI layer. */
    override fun sendKey(keysym: Int, down: Boolean) = synchronized(writeLock) {
        require(keysym >= 0) { "invalid RFB keysym" }
        output.writeByte(CLIENT_KEY_EVENT)
        output.writeByte(if (down) 1 else 0)
        output.writeShort(0)
        output.writeInt(keysym)
        output.flush()
    }

    override fun close() {
        socket.close()
    }

    private fun readFramebufferUpdate(): RfbFrame {
        input.skipFully(1)
        val rectangles = input.readUnsignedShort()
        repeat(rectangles) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val width = input.readUnsignedShort()
            val height = input.readUnsignedShort()
            when (val encoding = input.readInt()) {
                ENCODING_RAW -> readRawRectangle(x, y, width, height)
                ENCODING_DESKTOP_SIZE -> resizeFramebuffer(width, height)
                else -> throw IOException("unsupported RFB encoding $encoding")
            }
        }
        return RfbFrame(server.width, server.height, framebuffer.copyOf())
    }

    private fun readRawRectangle(x: Int, y: Int, width: Int, height: Int) {
        requireRectangle(x, y, width, height)
        val bytes = ByteArray(checkedPixelCount(width, height) * BYTES_PER_PIXEL)
        input.readFully(bytes)
        var offset = 0
        for (row in y until y + height) {
            var target = row * server.width + x
            repeat(width) {
                // The requested 32-bit little-endian format is B,G,R,pad.
                val blue = bytes[offset++].toInt() and 0xFF
                val green = bytes[offset++].toInt() and 0xFF
                val red = bytes[offset++].toInt() and 0xFF
                offset += 1
                framebuffer[target++] = 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
            }
        }
    }

    private fun resizeFramebuffer(width: Int, height: Int) {
        require(width > 0 && height > 0) { "invalid RFB desktop size" }
        val pixelCount = checkedPixelCount(width, height)
        server.width = width
        server.height = height
        framebuffer = IntArray(pixelCount)
    }

    private fun requireRectangle(x: Int, y: Int, width: Int, height: Int) {
        require(width > 0 && height > 0 && x >= 0 && y >= 0 &&
            x + width <= server.width && y + height <= server.height
        ) { "RFB rectangle is outside framebuffer" }
    }

    private fun checkedPixelCount(width: Int, height: Int): Int {
        val count = width.toLong() * height.toLong()
        require(count in 1..MAX_FRAMEBUFFER_PIXELS) { "RFB framebuffer is too large" }
        return count.toInt()
    }

    private fun readBoundedLength(max: Int): Int {
        val value = input.readInt()
        if (value < 0 || value > max) throw IOException("RFB payload exceeds limit")
        return value
    }

    private fun DataInputStream.skipFully(count: Int) {
        var remaining = count
        while (remaining > 0) {
            val skipped = skipBytes(remaining)
            if (skipped <= 0) {
                if (read() < 0) throw EOFException("unexpected end of RFB stream")
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }

    companion object {
        fun connect(host: String = LOOPBACK_HOST, port: Int, timeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS): RfbClient {
            require(host == LOOPBACK_HOST || host == LOOPBACK_IPV6) { "RFB is restricted to loopback" }
            require(port in 1..65535) { "invalid RFB port" }
            require(timeoutMs in 1..MAX_CONNECT_TIMEOUT_MS) { "invalid RFB timeout" }
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.tcpNoDelay = true
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val server = negotiate(input, output)
                return RfbClient(socket, input, output, server)
            } catch (error: Exception) {
                try {
                    socket.close()
                } catch (_: IOException) {
                    // Preserve the real handshake failure.
                }
                throw error
            }
        }

        private fun negotiate(input: DataInputStream, output: DataOutputStream): RfbServerInfo {
            val bannerBytes = ByteArray(PROTOCOL_BANNER_BYTES)
            input.readFully(bannerBytes)
            val banner = bannerBytes.toString(Charsets.US_ASCII)
            if (!PROTOCOL_BANNER.matches(banner)) throw IOException("invalid RFB protocol banner")
            val minor = banner.substring(8, 11).toIntOrNull() ?: throw IOException("invalid RFB version")
            val selectedMinor = when {
                minor >= 8 -> 8
                minor >= 7 -> 7
                minor == 3 -> 3
                else -> throw IOException("unsupported RFB version")
            }
            output.write("RFB 003.%03d\n".format(selectedMinor).toByteArray(Charsets.US_ASCII))
            output.flush()

            if (selectedMinor == 3) {
                if (input.readInt() != SECURITY_NONE) throw IOException("RFB server requires unsupported security")
            } else {
                val count = input.readUnsignedByte()
                if (count == 0) throw IOException("RFB server rejected connection: ${readFailureReason(input)}")
                val types = ByteArray(count)
                input.readFully(types)
                if (types.none { it.toInt() and 0xFF == SECURITY_NONE }) {
                    throw IOException("RFB server requires authentication")
                }
                output.writeByte(SECURITY_NONE)
                output.flush()
                val securityResult = input.readInt()
                if (securityResult != 0) {
                    val reason = if (selectedMinor >= 8) readFailureReason(input) else "server rejected connection"
                    throw IOException("RFB security negotiation failed: $reason")
                }
            }
            output.writeByte(1) // shared desktop
            output.flush()

            val width = input.readUnsignedShort()
            val height = input.readUnsignedShort()
            if (width <= 0 || height <= 0 || width.toLong() * height > MAX_FRAMEBUFFER_PIXELS) {
                throw IOException("invalid RFB framebuffer size")
            }
            input.readFully(ByteArray(PIXEL_FORMAT_BYTES))
            val nameLength = input.readInt()
            if (nameLength < 0 || nameLength > MAX_NAME_BYTES) throw IOException("RFB desktop name exceeds limit")
            val nameBytes = ByteArray(nameLength)
            input.readFully(nameBytes)
            setTrueColorPixelFormat(output)
            setEncodings(output)
            return RfbServerInfo(width, height, nameBytes.toString(Charsets.UTF_8), selectedMinor)
        }

        private fun setTrueColorPixelFormat(output: DataOutputStream) {
            output.writeByte(CLIENT_SET_PIXEL_FORMAT)
            output.write(ByteArray(3))
            output.writeByte(32)
            output.writeByte(24)
            output.writeByte(0) // little endian
            output.writeByte(1) // true colour
            output.writeShort(255)
            output.writeShort(255)
            output.writeShort(255)
            output.writeByte(16)
            output.writeByte(8)
            output.writeByte(0)
            output.write(ByteArray(3))
            output.flush()
        }

        private fun setEncodings(output: DataOutputStream) {
            output.writeByte(CLIENT_SET_ENCODINGS)
            output.writeByte(0)
            output.writeShort(2)
            output.writeInt(ENCODING_RAW)
            output.writeInt(ENCODING_DESKTOP_SIZE)
            output.flush()
        }

        private fun readFailureReason(input: DataInputStream): String {
            val size = input.readInt()
            if (size < 0 || size > MAX_FAILURE_REASON_BYTES) return "unknown error"
            val bytes = ByteArray(size)
            input.readFully(bytes)
            return bytes.toString(Charsets.UTF_8).take(256)
        }

        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val LOOPBACK_IPV6 = "::1"
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        private const val MAX_CONNECT_TIMEOUT_MS = 30_000
        private const val PROTOCOL_BANNER_BYTES = 12
        private val PROTOCOL_BANNER = Regex("RFB 003\\.[0-9]{3}\\n")
        private const val SECURITY_NONE = 1
        private const val PIXEL_FORMAT_BYTES = 16
        private const val CLIENT_SET_PIXEL_FORMAT = 0
        private const val CLIENT_SET_ENCODINGS = 2
        private const val CLIENT_FRAMEBUFFER_UPDATE_REQUEST = 3
        private const val CLIENT_KEY_EVENT = 4
        private const val CLIENT_POINTER_EVENT = 5
        private const val SERVER_FRAMEBUFFER_UPDATE = 0
        private const val SERVER_BELL = 2
        private const val SERVER_CUT_TEXT = 3
        private const val ENCODING_RAW = 0
        private const val ENCODING_DESKTOP_SIZE = -223
        private const val BYTES_PER_PIXEL = 4
        private const val MAX_FRAMEBUFFER_PIXELS = 16L * 1024L * 1024L
        private const val MAX_NAME_BYTES = 4 * 1024
        private const val MAX_FAILURE_REASON_BYTES = 4 * 1024
        private const val MAX_CLIPBOARD_BYTES = 64 * 1024
    }
}

internal data class RfbServerInfo(
    var width: Int,
    var height: Int,
    val desktopName: String,
    val protocolMinor: Int
)

/** Immutable framebuffer snapshot for a renderer to paint on the UI thread. */
internal data class RfbFrame(
    val width: Int,
    val height: Int,
    val argb: IntArray
)
