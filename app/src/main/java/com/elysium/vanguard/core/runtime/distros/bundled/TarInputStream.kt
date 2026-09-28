package com.elysium.vanguard.core.runtime.distros.bundled

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * PHASE 140 — a tiny tar reader.
 *
 * Android's stdlib does NOT include `java.util.jar` or
 * `java.util.tar` (those are JDK-only). The platform's
 * `org.apache.commons.compress` is not a dependency. We
 * need a self-contained tar reader for the
 * [BundledRootfsExtractor], and the only thing the
 * extractor has to read is `.tar.gz` files that **we
 * control** (rootfs tarballs produced by Alpine's
 * upstream `mkimage`).
 *
 * Scope of this reader:
 *   - USTAR / POSIX tar (the format Alpine minirootfs uses).
 *   - GNU long-name extension (typeflag 'L') for paths
 *     longer than 100 chars.
 *   - Regular files (typeflag '0' or `\u0000`).
 *   - Directories (typeflag '5').
 *   - Symlinks (typeflag '2') — recorded as a
 *     [TarEntry] of [TarEntryType.SYMLINK] with [TarEntry.linkTarget]
 *     populated. PHASE 145: the extractor now actually
 *     creates the symlink with
 *     `java.nio.file.Files.createSymbolicLink`. Phase
 *     140 used to drop the link and write a 0-byte
 *     regular file, which broke busybox-style rootfs
 *     (the shell entry was a symlink → 0-byte file →
 *     execve ENOENT). Symlinks are safe on the target
 *     storage (the app's internal `filesDir` —
 *     ext4/F2FS, never FAT/exFAT).
 *   - PAX extended headers (typeflag 'x' / 'g') are
 *     ignored; we never need them for our own tarballs.
 *
 * The reader is intentionally NOT a full POSIX tar
 * implementation. It is just enough to unpack the
 * tarballs the platform bundles.
 */
internal class TarInputStream(
    private val input: InputStream,
) : InputStream() {

    private var entryHeader: TarEntry? = null
    private var entryRemaining: Long = 0L
    private var pendingLongName: String? = null

    /**
     * Reads the next entry's header. Returns null at
     * end-of-archive. After this call, the stream is
     * positioned to read the entry's data.
     */
    @Throws(IOException::class)
    fun nextEntry(): TarEntry? {
        // Close out any previous entry's data first.
        skipRemainingEntryData()

        val rawHeader = ByteArray(HEADER_SIZE)
        val read = readFully(rawHeader)
        if (read == 0) return null // clean EOF
        if (read != HEADER_SIZE) {
            throw IOException("Truncated tar header (got $read bytes)")
        }
        if (isAllZeros(rawHeader)) {
            // Two consecutive zero blocks = end of archive.
            return null
        }

        val name = parseName(rawHeader)
        val mode = parseOctal(rawHeader, 100, 8)
        val size = parseOctal(rawHeader, 124, 12)
        val typeFlag = rawHeader[156].toInt().toChar()
        val linkTarget = parseString(rawHeader, 157, 100).trimEnd('\u0000')
        val magic = parseString(rawHeader, 257, 6)
        // Accept both POSIX ("ustar\0") and GNU ("ustar ")
        // magic. Alpine's minirootfs uses GNU format.
        if (magic != "ustar" && magic != "ustar ") {
            throw IOException(
                "Tar entry is not USTAR (magic='$magic', name='$name')"
            )
        }

        val resolvedName = pendingLongName?.let { longName ->
            pendingLongName = null
            // Long-name entries have empty size; their data
            // block (already consumed) carries the actual
            // name as UTF-8. We already read the data block
            // — but the long-name data is *after* the
            // header, and our header-reader does not read
            // the data. Re-do: when typeflag is 'L', the
            // *next* entry is the real one and its header
            // name should be replaced by the long-name's
            // data, which we have to read now.
            // For simplicity, fall back to combining the
            // long-name with the prefix.
            if (longName.isNotEmpty()) longName else name
        } ?: name

        if (typeFlag == 'L') {
            // GNU long-name. Read the data block (size
            // bytes), treat it as UTF-8 name, then recurse
            // to read the real entry header.
            val nameBytes = ByteArray(size.toInt())
            readFullyInto(nameBytes)
            pendingLongName = String(nameBytes, Charsets.UTF_8).trimEnd('\u0000')
            skipPadding(size)
            return nextEntry()
        }

        val type = when (typeFlag) {
            '0', '\u0000' -> if (size == 0L && name.endsWith("/")) TarEntryType.DIRECTORY else TarEntryType.FILE
            '5' -> TarEntryType.DIRECTORY
            '2' -> TarEntryType.SYMLINK
            'x', 'g' -> {
                // Pax extended header — skip the data and
                // recurse.
                skipPadding(size)
                return nextEntry()
            }
            else -> TarEntryType.FILE
        }

        val resolvedMode = if (type == TarEntryType.DIRECTORY) {
            if (mode == 0L) 0x1ED.toLong() else mode
        } else {
            if (mode == 0L) 0x1A4.toLong() else mode
        }

        val entry = TarEntry(
            name = resolvedName,
            type = type,
            mode = resolvedMode,
            size = if (type == TarEntryType.DIRECTORY) 0L else size,
            linkTarget = if (type == TarEntryType.SYMLINK) linkTarget else null,
        )
        entryHeader = entry
        entryRemaining = entry.size
        return entry
    }

    private fun skipRemainingEntryData() {
        if (entryHeader == null) return
        if (entryRemaining > 0) {
            discardBytes(entryRemaining)
        }
        // Pad to 512 boundary.
        if (entryHeader!!.size > 0) {
            val pad = (BLOCK_SIZE - (entryHeader!!.size % BLOCK_SIZE).toInt()) % BLOCK_SIZE
            if (pad > 0) discardBytes(pad.toLong())
        }
        entryHeader = null
        entryRemaining = 0L
    }

    private fun skipPadding(size: Long) {
        val pad = (BLOCK_SIZE - (size % BLOCK_SIZE).toInt()) % BLOCK_SIZE
        if (pad > 0) discardBytes(pad.toLong())
    }

    /**
     * Reads and discards exactly [count] bytes from the
     * underlying stream. We do NOT use
     * [InputStream.skip] because the stream may be a
     * `GZIPInputStream`, whose `skip` implementation is
     * unreliable: it claims to skip the requested count
     * even when it has only advanced through a deflate
     * block, leaving the stream misaligned. Reading into
     * a small buffer and throwing the bytes away is
     * slower but byte-accurate.
     */
    private fun discardBytes(count: Long) {
        val buf = ByteArray(4096)
        var remaining = count
        while (remaining > 0) {
            val toRead = minOf(remaining, buf.size.toLong()).toInt()
            val n = input.read(buf, 0, toRead)
            if (n < 0) throw IOException("Unexpected EOF while discarding $count bytes")
            remaining -= n
        }
    }

    /** Reads from [input] until [buf] is full or EOF. Returns the number of bytes read. */
    private fun readFully(buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return if (off == 0) 0 else off
            off += n
        }
        return off
    }

    private fun readFullyInto(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException("Unexpected EOF in tar data")
            off += n
        }
    }

    /** Reads a single byte from the current entry's data. */
    override fun read(): Int {
        if (entryHeader == null) {
            throw IOException("read() called outside an entry")
        }
        if (entryRemaining <= 0) return -1
        val b = input.read()
        if (b < 0) return -1
        entryRemaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (entryHeader == null) {
            throw IOException("read() called outside an entry")
        }
        if (entryRemaining <= 0) return -1
        val toRead = minOf(len.toLong(), entryRemaining).toInt()
        val n = input.read(b, off, toRead)
        if (n < 0) return -1
        entryRemaining -= n
        return n
    }

    override fun close() {
        skipRemainingEntryData()
        input.close()
    }

    private fun parseName(header: ByteArray): String {
        val name = parseString(header, 0, 100)
        val prefix = parseString(header, 345, 155)
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    private fun parseString(header: ByteArray, offset: Int, length: Int): String {
        var end = offset + length
        while (end > offset && header[end - 1].toInt() == 0) end--
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    /**
     * Parses a tar octal field. Tar octal fields are
     * null-terminated, but they may also use a leading
     * 0x80 byte to indicate a binary (big-endian) value
     * — modern tar implementations sometimes use the
     * binary form for very large sizes (>8 GiB). We do
     * not need to support >8 GiB entries, so we treat
     * the binary form as malformed.
     */
    private fun parseOctal(header: ByteArray, offset: Int, length: Int): Long {
        var end = offset + length
        while (end > offset && header[end - 1].toInt() == 0) end--
        if (end == offset) return 0L
        var value = 0L
        for (i in offset until end) {
            val c = header[i].toInt().toChar()
            if (c !in '0'..'7') {
                throw IOException("Non-octal digit in tar field at $offset: '$c'")
            }
            value = value * 8 + (c - '0')
        }
        return value
    }

    private fun isAllZeros(buf: ByteArray): Boolean {
        for (b in buf) if (b.toInt() != 0) return false
        return true
    }

    private companion object {
        const val HEADER_SIZE = 512
        const val BLOCK_SIZE = 512
    }
}

/**
 * A single tar entry as exposed by [TarInputStream.nextEntry].
 *
 * @property name         Path inside the archive (forward
 *                       slashes; relative to the archive
 *                       root).
 * @property type         [TarEntryType.FILE], [TarEntryType.DIRECTORY],
 *                       or [TarEntryType.SYMLINK].
 * @property mode         Unix mode bits (lower 9 bits =
 *                       rwx for owner/group/other; the
 *                       extractor applies the executable
 *                       bit when the owner's execute bit
 *                       is set).
 * @property size         File size in bytes (0 for
 *                       directories + symlinks).
 * @property linkTarget   For symlinks, the target path.
 */
internal data class TarEntry(
    val name: String,
    val type: TarEntryType,
    val mode: Long,
    val size: Long,
    val linkTarget: String?,
) {
    val isDirectory: Boolean get() = type == TarEntryType.DIRECTORY
}

internal enum class TarEntryType { FILE, DIRECTORY, SYMLINK }
