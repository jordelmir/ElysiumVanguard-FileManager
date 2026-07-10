package com.elysium.vanguard.core.runtime.distros

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * PHASE 9.6.3.3 — Counts bytes read from an [InputStream] so we can
 * surface progress per byte instead of per entry.
 *
 * Why a thin [FilterInputStream] subclass: we only need to add
 * behavior on `read`, not allocate any new memory. The byte counter
 * is a `Long`; even a 4 GB download fits comfortably.
 *
 * Consumers of `available()` see the underlying stream's value — we
 * deliberately don't lie about that, because callers use it for
 * efficient buffer sizing. The `progressBytes` field is the only
 * surface we add.
 *
 * Concurrency: this class is not thread-safe; callers should drive
 * `read` from a single thread (the installer's IO thread is one).
 *
 * Phase 9.6.3.3 — first build; intentionally minimal.
 */
class ProgressInputStream(
    private val inner: InputStream,
    private val onProgress: (bytesRead: Long) -> Unit
) : FilterInputStream(inner) {

    /** Total bytes successfully read so far. */
    var progressBytes: Long = 0L
        private set

    @Throws(IOException::class)
    override fun read(): Int {
        val b = super.read()
        if (b >= 0) {
            progressBytes += 1
            onProgress(1L)
        }
        return b
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) {
            progressBytes += n
            onProgress(n.toLong())
        }
        return n
    }

    /**
     * Adapter for code that already has a stream and wants to add
     * progress callbacks without nesting another copy of the file.
     */
    class Adapter(
        private val onProgress: (Long) -> Unit
    ) {
        fun wrap(stream: InputStream): ProgressInputStream =
            ProgressInputStream(stream, onProgress)
    }
}
