package com.elysium.vanguard.core.runtime.distros

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * PHASE 9.6.2 — Pure-Kotlin tar / tar.gz extractor for distro rootfs.
 *
 * Why we don't use Apache Commons Compress here: we want a simple, fast
 * extractor that consumes a stream (the download) and writes entries
 * verbatim onto disk. We don't need selective extraction, symlink handling
 * beyond basic cases, or pax extensions. A targeted implementation that
 * knows exactly what we need is ~150 LOC and dead-simple to audit.
 *
 * Scope:
 *   - ustar (POSIX.1-1988) tar archive, with optional gzip wrapping.
 *   - Plain '0' (regular file), '5' (directory), '2' (symlink) types.
 *   - Block size 512 bytes; entry header 512 bytes; entries 512-aligned.
 *
 * Out of scope for 9.6.2:
 *   - POSIX.1-2001 / pax extended headers (skip with a warning).
 *   - Hard links, device nodes, FIFOs.
 *   - xz / zstd / bzip2 wrappers (added in 9.6.3).
 *
 * Phase 9.6.2 — first build; intentionally minimal.
 */
class DistroRootfsExtractor {

    /**
     * Extract [stream] (gzip-wrapped per [kind]) into [destDir], invoking
     * [progress] once per entry written. Returns the number of entries.
     *
     * Note: [RootfsKind.TarXz] and [RootfsKind.DockerLayer] are NOT
     * handled here — callers must pre-decompress xz via
     * `org.apache.commons.compress.compressors.xz.XZCompressorInputStream`
     * and call [extractRawTar] directly.
     *
     * @throws IOException on any I/O failure. Caller is responsible for
     *                     cleaning up partial extractions.
     */
    fun extract(
        stream: InputStream,
        destDir: File,
        kind: RootfsKind,
        progress: ProgressCallback = ProgressCallback.NONE
    ): ExtractResult {
        destDir.mkdirs()
        val source = when (kind) {
            RootfsKind.TarGz -> GZIPInputStream(stream)
            RootfsKind.TarXz -> throw IOException(
                "xz extraction: pre-decompress then call extractRawTar"
            )
            RootfsKind.BootstrapTarball,
            RootfsKind.DockerLayer -> throw IOException(
                "$kind extraction not supported in 9.6.3.2"
            )
            RootfsKind.Custom -> stream // raw tar
        }
        return readTar(source, destDir, progress)
    }

    /**
     * PHASE 9.6.3.2 — Extract a TAR archive from a stream that's already
     * been decompressed (no wrapper handling here).
     *
     * This is the seam the custom rootfs installer uses: it handles the
     * xz / bzip2 / zst wrapping and then hands the raw tar to us.
     */
    fun extractRawTar(
        stream: InputStream,
        destDir: File,
        progress: ProgressCallback = ProgressCallback.NONE
    ): ExtractResult {
        destDir.mkdirs()
        return readTar(stream, destDir, progress)
    }

    private fun readTar(
        stream: InputStream,
        destDir: File,
        progress: ProgressCallback
    ): ExtractResult {
        var entryCount = 0
        var totalBytes = 0L
        while (true) {
            val header = readHeader(stream) ?: break
            val name = header.name
            if (name.isEmpty()) break
            val target = File(destDir, name)
            when (header.typeFlag) {
                TYPE_REGULAR -> {
                    target.parentFile?.mkdirs()
                    val written = copyData(stream, target, header.size)
                    entryCount += 1
                    totalBytes += written
                    progress.onEntry(name, written)
                }
                TYPE_DIRECTORY -> {
                    target.mkdirs()
                    entryCount += 1
                    progress.onEntry(name, 0L)
                }
                TYPE_SYMLINK -> {
                    target.parentFile?.mkdirs()
                    target.delete()
                    val linkTarget = header.linkName.ifEmpty { "/" }
                    // Runtime.getRuntime available in JVM unit tests; we
                    // use Files.createSymbolicLink when possible and
                    // fall back to a sentinel text file with the target
                    // when symlinks are unsupported (some Android FS).
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= 0) {
                            java.nio.file.Files.createSymbolicLink(
                                target.toPath(),
                                java.nio.file.Paths.get(linkTarget)
                            )
                        }
                    } catch (_: UnsupportedOperationException) {
                        target.writeText("symlink -> $linkTarget\n")
                    }
                    entryCount += 1
                    progress.onEntry(name, 0L)
                }
                TYPE_PAX, TYPE_GNU_LONG_NAME -> {
                    // Phase 9.6.3 will interpret pax extended headers. For
                    // now we skip past the data block when present.
                    skipData(stream, header.size)
                }
                else -> {
                    // Unknown type: skip the data block and continue.
                    skipData(stream, header.size)
                }
            }
            // tar entries are 512-byte aligned. We've read header.size
            // bytes of payload; if it isn't a multiple of 512, pad.
            padTo512(stream, header.size)
        }
        return ExtractResult(entriesExtracted = entryCount, bytesWritten = totalBytes)
    }

    private fun copyData(
        stream: InputStream,
        target: File,
        bytes: Long
    ): Long = target.outputStream().use { out ->
        val buf = ByteArray(BLOCK_SIZE)
        var remaining = bytes
        var total = 0L
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val n = stream.read(buf, 0, toRead)
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
            total += n
        }
        total
    }

    private fun skipData(stream: InputStream, bytes: Long) {
        var remaining = bytes
        val buf = ByteArray(BLOCK_SIZE)
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val n = stream.read(buf, 0, toRead)
            if (n < 0) break
            remaining -= n
        }
        padTo512(stream, bytes)
    }

    private fun padTo512(stream: InputStream, payloadBytes: Long) {
        val pad = (BLOCK_SIZE - (payloadBytes % BLOCK_SIZE)) % BLOCK_SIZE
        if (pad > 0) {
            val buf = ByteArray(pad.toInt())
            stream.read(buf)
        }
    }

    /**
     * Read one 512-byte header. Returns null when the header is all zeros
     * (the standard tar end-of-archive marker, and how we know we've
     * reached the end).
     */
    private fun readHeader(stream: InputStream): TarHeader? {
        val headerBytes = ByteArray(BLOCK_SIZE)
        var read = 0
        while (read < BLOCK_SIZE) {
            val n = stream.read(headerBytes, read, BLOCK_SIZE - read)
            if (n < 0) return null
            read += n
        }
        if (isAllZeros(headerBytes)) return null
        return TarHeader(
            name = String(
                headerBytes,
                NAME_OFFSET,
                NAME_LENGTH,
                Charsets.UTF_8
            ).trimEnd('\u0000', ' '),
            size = parseOctal(headerBytes, SIZE_OFFSET, SIZE_LENGTH),
            typeFlag = headerBytes[TYPE_OFFSET].toInt() and 0xFF,
            linkName = String(
                headerBytes,
                LINK_OFFSET,
                LINK_LENGTH,
                Charsets.UTF_8
            ).trimEnd('\u0000', ' ')
        )
    }

    private fun parseOctal(bytes: ByteArray, offset: Int, length: Int): Long {
        // ustar octal stores null-terminated ASCII digits.
        val s = String(
            bytes,
            offset,
            length,
            Charsets.US_ASCII
        ).trimEnd('\u0000', ' ')
        if (s.isEmpty()) return 0L
        return try {
            s.toLong(8)
        } catch (_: NumberFormatException) {
            0L
        }
    }

    private fun isAllZeros(bytes: ByteArray): Boolean {
        for (b in bytes) if (b != 0.toByte()) return false
        return true
    }

    data class TarHeader(
        val name: String,
        val size: Long,
        val typeFlag: Int,
        val linkName: String
    )

    data class ExtractResult(
        val entriesExtracted: Int,
        val bytesWritten: Long
    )

    /**
     * Optional callback for each entry written. Implementations should be
     * cheap; they fire on every entry of a multi-thousand-entry rootfs.
     */
    fun interface ProgressCallback {
        fun onEntry(name: String, bytes: Long)
        companion object {
            val NONE = ProgressCallback { _, _ -> }
        }
    }

    companion object {
        private const val BLOCK_SIZE = 512
        private const val NAME_OFFSET = 0
        private const val NAME_LENGTH = 100
        private const val SIZE_OFFSET = 124
        private const val SIZE_LENGTH = 12
        private const val TYPE_OFFSET = 156
        private const val LINK_OFFSET = 157
        private const val LINK_LENGTH = 100

        private const val TYPE_REGULAR = '0'.code
        private const val TYPE_DIRECTORY = '5'.code
        private const val TYPE_SYMLINK = '2'.code
        private const val TYPE_PAX = 'x'.code
        private const val TYPE_GNU_LONG_NAME = 'L'.code
    }
}

/**
 * Network/HTTP-agnostic download helper. We keep this here so the install
 * pipeline owns every byte; the HTTP transport is intentionally separated
 * (see [DistroInstaller]) so unit tests can fake it.
 */
fun interface DistroHttpDownloader {
    /**
     * Open a stream for [url]. Caller closes the stream. Throws
     * [IOException] on non-2xx.
     */
    fun open(url: String): InputStream
}
