package com.elysium.vanguard.core.runtime.terminal.pty

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Narrow JVM facade for the Rust-owned Android PTY runtime.
 *
 * The native layer owns the file descriptor, child PID, process group and
 * wait/reap lifecycle. Kotlin only owns the opaque handle and must never use
 * reflection or hidden APIs to access a raw descriptor.
 */
internal class NativePty private constructor(
    private val handle: Long,
    val pid: Long
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    fun read(destination: ByteArray, timeoutMs: Int = READ_TIMEOUT_MS): Int {
        require(destination.isNotEmpty()) { "destination must not be empty" }
        ensureOpen()
        return NativePtyBridge.nativeRead(handle, destination, 0, destination.size, timeoutMs)
    }

    fun write(source: ByteArray): Int {
        if (source.isEmpty()) return 0
        ensureOpen()
        var offset = 0
        while (offset < source.size) {
            val count = minOf(MAX_WRITE_CHUNK, source.size - offset)
            val written = NativePtyBridge.nativeWrite(handle, source, offset, count)
            if (written <= 0) throw IOException("Native PTY made no write progress")
            offset += written
        }
        return offset
    }

    fun resize(columns: Int, rows: Int) {
        require(columns > 0 && rows > 0) { "terminal size must be positive" }
        ensureOpen()
        if (!NativePtyBridge.nativeResize(handle, rows, columns)) {
            throw IOException("Native PTY rejected terminal resize")
        }
    }

    fun signal(signal: Int) {
        ensureOpen()
        if (!NativePtyBridge.nativeSignal(handle, signal)) {
            throw IOException("Native PTY rejected signal $signal")
        }
    }

    /** Returns null while the child is still alive. Negative values are signals. */
    fun waitForExit(timeoutMs: Int): Int? {
        ensureOpen()
        return when (val result = NativePtyBridge.nativeWait(handle, timeoutMs)) {
            NativePtyBridge.STATUS_RUNNING -> null
            NativePtyBridge.STATUS_CLOSED,
            NativePtyBridge.STATUS_UNKNOWN -> throw IOException("Native PTY exit status is unavailable")
            else -> result
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        NativePtyBridge.nativeClose(handle, CLOSE_GRACE_MS)
    }

    private fun ensureOpen() {
        check(!closed.get()) { "Native PTY is closed" }
    }

    companion object {
        private const val READ_TIMEOUT_MS = 250
        private const val CLOSE_GRACE_MS = 750
        private const val MAX_WRITE_CHUNK = 64 * 1024

        fun spawn(
            command: List<String>,
            environment: Map<String, String>,
            workingDirectory: File?,
            columns: Int,
            rows: Int
        ): NativePty {
            require(command.isNotEmpty()) { "command must not be empty" }
            require(command.none { '\u0000' in it }) { "command cannot contain NUL" }
            require(environment.keys.all(::isEnvironmentKey)) { "invalid environment key" }
            require(environment.values.none { '\u0000' in it }) { "environment cannot contain NUL" }
            if (!NativePtyBridge.isAvailable()) {
                throw IOException(NativePtyBridge.unavailableReason())
            }
            val entries = environment.entries.map { (key, value) -> "$key=$value" }.toTypedArray()
            val handle = NativePtyBridge.nativeSpawn(
                command.toTypedArray(),
                entries,
                workingDirectory?.absolutePath,
                rows,
                columns
            )
            if (handle <= 0L) throw IOException("Native PTY returned an invalid handle")
            try {
                return NativePty(handle, NativePtyBridge.nativePid(handle))
            } catch (failure: Throwable) {
                NativePtyBridge.nativeClose(handle, CLOSE_GRACE_MS)
                throw failure
            }
        }

        private fun isEnvironmentKey(key: String): Boolean =
            key.isNotEmpty() && '=' !in key && '\u0000' !in key
    }
}

/** JNI declarations are intentionally package-private and non-overloaded. */
internal object NativePtyBridge {
    const val STATUS_RUNNING: Int = Int.MIN_VALUE
    const val STATUS_CLOSED: Int = Int.MIN_VALUE + 1
    const val STATUS_UNKNOWN: Int = Int.MIN_VALUE + 2

    private val loadFailure: Throwable? by lazy {
        runCatching { System.loadLibrary("elysium_runtime") }.exceptionOrNull()
    }

    fun isAvailable(): Boolean = loadFailure == null && nativeIsSupported()

    fun unavailableReason(): String = loadFailure?.let {
        "Native PTY library could not load: ${it.message ?: it::class.java.simpleName}"
    } ?: "Native PTY is not supported by this device"

    @JvmStatic private external fun nativeIsSupported(): Boolean
    @JvmStatic external fun nativeSpawn(
        argv: Array<String>,
        environment: Array<String>,
        workingDirectory: String?,
        rows: Int,
        columns: Int
    ): Long

    @JvmStatic external fun nativePid(handle: Long): Long
    @JvmStatic external fun nativeRead(
        handle: Long,
        destination: ByteArray,
        offset: Int,
        length: Int,
        timeoutMs: Int
    ): Int

    @JvmStatic external fun nativeWrite(
        handle: Long,
        source: ByteArray,
        offset: Int,
        length: Int
    ): Int

    @JvmStatic external fun nativeResize(handle: Long, rows: Int, columns: Int): Boolean
    @JvmStatic external fun nativeSignal(handle: Long, signal: Int): Boolean
    @JvmStatic external fun nativeWait(handle: Long, timeoutMs: Int): Int
    @JvmStatic external fun nativeClose(handle: Long, graceMs: Int): Int
}
