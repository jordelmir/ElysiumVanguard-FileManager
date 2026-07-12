package com.elysium.vanguard.core.runtime.distros

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

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
class DistroRootfsExtractor(
    private val limits: ExtractionLimits = ExtractionLimits()
) {

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
        progress: ProgressCallback = ProgressCallback.NONE,
        stripComponents: Int = 0
    ): ExtractResult {
        require(stripComponents >= 0) { "stripComponents must be non-negative" }
        ensureDestination(destDir)
        val source = when (kind) {
            RootfsKind.TarGz -> GZIPInputStream(stream)
            RootfsKind.TarXz -> XZCompressorInputStream(stream)
            RootfsKind.BootstrapTarball,
            RootfsKind.DockerLayer -> throw IOException(
                "$kind extraction not supported in 9.6.3.2"
            )
            RootfsKind.Custom -> stream // raw tar
        }
        return readTar(source, destDir, progress, stripComponents)
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
        progress: ProgressCallback = ProgressCallback.NONE,
        stripComponents: Int = 0
    ): ExtractResult {
        require(stripComponents >= 0) { "stripComponents must be non-negative" }
        ensureDestination(destDir)
        return readTar(stream, destDir, progress, stripComponents)
    }

    private fun readTar(
        stream: InputStream,
        destDir: File,
        progress: ProgressCallback,
        stripComponents: Int
    ): ExtractResult {
        val base = destDir.canonicalFile
        val pendingHardLinks = ArrayList<Pair<File, String>>()
        val pendingSymbolicLinks = ArrayList<Pair<File, String>>()
        var entryCount = 0
        var seenEntries = 0
        var totalBytes = 0L

        TarArchiveInputStream(stream).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                seenEntries += 1
                if (seenEntries > limits.maxEntries) {
                    throw IOException("Archive exceeds ${limits.maxEntries} entry limit")
                }
                if (entry.name.length > limits.maxPathLength) {
                    throw IOException("Archive path exceeds ${limits.maxPathLength} characters")
                }
                if (!tar.canReadEntryData(entry)) {
                    throw IOException("Unsupported tar entry: ${entry.name}")
                }
                val entryName = stripArchivePath(entry.name, stripComponents) ?: continue
                val target = safeTarget(base, entryName)
                when {
                    entry.isDirectory -> {
                        if (!target.isDirectory && !target.mkdirs()) {
                            throw IOException("Could not create directory: $entryName")
                        }
                        applyMode(target, entry.mode)
                        entryCount += 1
                        progress.onEntry(entryName, 0L)
                    }
                    entry.isSymbolicLink -> {
                        validateSymlinkTarget(base, target, entry.linkName)
                        // Materialize after regular files. Besides preventing
                        // links from redirecting later archive writes, this
                        // lets case-insensitive JVM test hosts preserve the
                        // real target when an archive contains aliases that
                        // differ only by case (Android's ext4 is case-sensitive).
                        pendingSymbolicLinks += target to entry.linkName
                        entryCount += 1
                        progress.onEntry(entryName, 0L)
                    }
                    entry.isLink -> {
                        target.parentFile?.mkdirs()
                        val linkName = stripArchivePath(entry.linkName, stripComponents)
                            ?: throw IOException("Hard-link target removed by strip: ${entry.linkName}")
                        pendingHardLinks += target to linkName
                        entryCount += 1
                        progress.onEntry(entryName, 0L)
                    }
                    entry.isFile -> {
                        validateEntrySize(entry, totalBytes)
                        target.parentFile?.mkdirs()
                        val written = copyEntry(tar, target, entry)
                        applyMode(target, entry.mode)
                        entryCount += 1
                        totalBytes += written
                        progress.onEntry(entryName, written)
                    }
                    else -> {
                        // Device nodes, FIFOs and sockets are supplied by
                        // proot bind mounts and must not be materialized.
                    }
                }
            }
        }

        pendingHardLinks.forEach { (target, linkName) ->
            val source = safeTarget(base, linkName)
            if (!source.isFile) {
                throw IOException("Hard-link source is missing: $linkName")
            }
            target.delete()
            try {
                Files.createLink(target.toPath(), source.toPath())
            } catch (_: Exception) {
                source.copyTo(target, overwrite = true)
            }
        }

        pendingSymbolicLinks.forEach { (target, linkName) ->
            target.parentFile?.mkdirs()
            if (isCaseInsensitiveSelfAlias(base, target, linkName)) {
                return@forEach
            }
            if (target.exists() && !target.delete()) {
                throw IOException("Could not replace symlink target: ${target.absolutePath}")
            }
            try {
                Files.createSymbolicLink(target.toPath(), Paths.get(linkName))
            } catch (e: Exception) {
                throw IOException("Could not create symlink ${target.name} -> $linkName", e)
            }
        }

        return ExtractResult(entriesExtracted = entryCount, bytesWritten = totalBytes)
    }

    private fun ensureDestination(destDir: File) {
        if (!destDir.isDirectory && !destDir.mkdirs()) {
            throw IOException("Could not create extraction directory: ${destDir.absolutePath}")
        }
    }

    private fun validateEntrySize(entry: TarArchiveEntry, bytesWritten: Long) {
        if (entry.size < 0L) {
            throw IOException("Negative tar entry size: ${entry.name}")
        }
        if (entry.size > limits.maxEntryBytes) {
            throw IOException("Tar entry exceeds ${limits.maxEntryBytes} bytes: ${entry.name}")
        }
        if (entry.size > limits.maxTotalBytes - bytesWritten) {
            throw IOException("Archive exceeds ${limits.maxTotalBytes} extracted-byte limit")
        }
    }

    private fun stripArchivePath(path: String, count: Int): String? {
        if (count == 0) return path.removePrefix("./").takeIf { it.isNotBlank() }
        val components = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (components.size <= count) return null
        return components.drop(count).joinToString("/")
    }

    private fun isCaseInsensitiveSelfAlias(base: File, target: File, linkName: String): Boolean {
        val rawLink = Paths.get(linkName)
        val resolved = if (rawLink.isAbsolute) {
            File(base, linkName.removePrefix("/"))
        } else {
            val parent = target.parentFile ?: return false
            parent.toPath().resolve(rawLink).normalize().toFile()
        }
        if (target.toPath().normalize() == resolved.toPath().normalize()) return true
        return try {
            target.exists() && resolved.exists() &&
                Files.isSameFile(target.toPath(), resolved.toPath())
        } catch (_: Exception) {
            false
        }
    }

    private fun safeTarget(base: File, archiveName: String): File {
        val cleanName = archiveName.removePrefix("./")
        val target = File(base, cleanName).canonicalFile
        val basePrefix = base.path + File.separator
        if (target != base && !target.path.startsWith(basePrefix)) {
            throw IOException("Unsafe tar path: $archiveName")
        }
        return target
    }

    private fun validateSymlinkTarget(base: File, target: File, linkName: String) {
        if (linkName.isBlank()) {
            throw IOException("Symlink has an empty target: ${target.name}")
        }
        if (linkName.length > limits.maxPathLength) {
            throw IOException("Symlink target exceeds ${limits.maxPathLength} characters")
        }
        val linkPath = Paths.get(linkName)
        val basePath = base.toPath().toAbsolutePath().normalize()
        val hostResolved = if (linkPath.isAbsolute) {
            basePath.resolve(linkName.removePrefix("/"))
        } else {
            (target.parentFile ?: base).toPath().resolve(linkPath)
        }.toAbsolutePath().normalize()
        if (!hostResolved.startsWith(basePath)) {
            throw IOException("Symlink escapes rootfs: ${target.name} -> $linkName")
        }
    }

    private fun copyEntry(
        tar: TarArchiveInputStream,
        target: File,
        entry: TarArchiveEntry
    ): Long = target.outputStream().use { output ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = entry.size
        var total = 0L
        while (remaining > 0L) {
            val read = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) {
                throw IOException("Unexpected end of tar entry: ${entry.name}")
            }
            output.write(buffer, 0, read)
            remaining -= read
            total += read
        }
        total
    }

    private fun applyMode(file: File, mode: Int) {
        file.setReadable(mode and 0b100_100_100 != 0, true)
        file.setWritable(mode and 0b010_010_010 != 0, true)
        file.setExecutable(mode and 0b001_001_001 != 0, true)
    }

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
        private const val DEFAULT_BUFFER_SIZE = 32 * 1024
    }
}

data class ExtractionLimits(
    val maxEntries: Int = 500_000,
    val maxTotalBytes: Long = 16L * 1024L * 1024L * 1024L,
    val maxEntryBytes: Long = 8L * 1024L * 1024L * 1024L,
    val maxPathLength: Int = 4096
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxTotalBytes > 0L) { "maxTotalBytes must be positive" }
        require(maxEntryBytes > 0L) { "maxEntryBytes must be positive" }
        require(maxEntryBytes <= maxTotalBytes) {
            "maxEntryBytes must not exceed maxTotalBytes"
        }
        require(maxPathLength > 0) { "maxPathLength must be positive" }
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
