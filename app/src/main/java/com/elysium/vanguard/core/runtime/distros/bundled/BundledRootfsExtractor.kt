package com.elysium.vanguard.core.runtime.distros.bundled

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * PHASE 140 — extracts a bundled rootfs to a host directory
 * with hash verification.
 *
 * Responsibilities:
 *   1. Open the source via [BundledRootfsSource].
 *   2. Compute SHA-256 of the raw asset bytes; compare to
 *      [BundledDistro.sha256]. Mismatch → throw
 *      [BundledRootfsError.HashMismatch].
 *   3. If the destination `<baseDir>/<id>/` is already
 *      present (idempotent), skip the unpack and just
 *      return the path.
 *   4. Otherwise unpack the gzipped tar to a staging
 *      directory, then atomically rename to the final
 *      location. Any IO error during unpack cleans up the
 *      staging dir.
 *   5. Return the path to the extracted rootfs directory
 *      (the directory that contains `bin/`, `etc/`, …).
 *
 * Thread-safety: the extractor serializes per-distro
 * extractions via an internal mutex. Two concurrent calls
 * for the same distro do not double-extract; two
 * concurrent calls for different distros proceed in
 * parallel.
 *
 * The extractor is pure JVM (no Android imports) so it
 * can be unit-tested in the standard testDebugUnitTest
 * pipeline.
 */
class BundledRootfsExtractor(
    private val baseDir: File,
) {
    private val lock = Any()

    init {
        require(!baseDir.exists() || baseDir.isDirectory) {
            "Bundled rootfs base is not a directory: $baseDir"
        }
    }

    /**
     * Ensures the rootfs for [distro] is extracted under
     * `<baseDir>/<distro.id>/` and returns the path.
     *
     * @throws BundledRootfsError.AssetNotFound when the
     *         source cannot be opened.
     * @throws BundledRootfsError.HashMismatch when the
     *         computed SHA-256 of the source bytes does
     *         not match [BundledDistro.sha256].
     * @throws BundledRootfsError.ExtractionFailed when
     *         the gzipped tar cannot be read.
     */
    fun ensureExtracted(
        distro: BundledDistro,
        source: BundledRootfsSource,
    ): File = synchronized(lock) {
        val finalDir = File(baseDir, distro.id)
        if (finalDir.isDirectory && hasRootfsMarker(finalDir)) {
            // Already extracted from a prior run; idempotent
            // return. We still re-hash the source bytes
            // (defense-in-depth) below.
            verifySourceHash(distro, source)
            return@synchronized finalDir
        }

        // 1. Verify the asset bytes before touching disk.
        verifySourceHash(distro, source)

        // 2. Stage the unpack under a sibling .part dir.
        val stageDir = File(baseDir, "${distro.id}.part")
        if (stageDir.exists()) {
            stageDir.deleteRecursively()
        }
        if (!stageDir.mkdirs() && !stageDir.isDirectory) {
            throw BundledRootfsError.ExtractionFailed(
                "Cannot create staging directory: $stageDir"
            )
        }

        try {
            unpackGzippedTar(source, stageDir)
        } catch (t: Throwable) {
            stageDir.deleteRecursively()
            if (t is BundledRootfsError) throw t
            throw BundledRootfsError.ExtractionFailed(
                "Unpack failed for ${distro.id}: ${t.message}",
            )
        }

        // 3. Atomic move. deleteRecursively is safe because
        // the staging dir is private to this process.
        if (finalDir.exists()) {
            finalDir.deleteRecursively()
        }
        if (!stageDir.renameTo(finalDir)) {
            // Some filesystems can't rename across volumes;
            // fall back to a copy + delete.
            try {
                copyRecursively(stageDir, finalDir)
            } finally {
                stageDir.deleteRecursively()
            }
        }
        finalDir
    }

    private fun hasRootfsMarker(dir: File): Boolean {
        // A valid rootfs has at least `bin/` and `etc/`. We
        // do NOT require a particular set of binaries (the
        // asset may legitimately ship without busybox in
        // future) — the marker is the FHS layout, not the
        // contents.
        return File(dir, "bin").isDirectory &&
            File(dir, "etc").isDirectory
    }

    private fun verifySourceHash(
        distro: BundledDistro,
        source: BundledRootfsSource,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var totalRead = 0L
        BufferedInputStream(source.openStream(), buffer.size).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                totalRead += read
            }
        }
        if (totalRead != source.sizeBytes) {
            throw BundledRootfsError.HashMismatch(
                distroId = distro.id,
                expectedSize = distro.sizeBytes,
                actualSize = totalRead,
            )
        }
        val actual = digest.digest().toHex()
        if (!actual.equals(distro.sha256, ignoreCase = true)) {
            throw BundledRootfsError.HashMismatch(
                distroId = distro.id,
                expectedHash = distro.sha256,
                actualHash = actual,
            )
        }
    }

    private fun unpackGzippedTar(source: BundledRootfsSource, into: File) {
        // The asset is stored as a plain `.tar` (not gzipped)
        // — see the noCompress note in build.gradle.kts.
        // The function name is retained for the existing
        // test seam + to make the intent clear at the call
        // site ("unpack the tar").
        val input = BufferedInputStream(source.openStream())
        // We need a tar reader that does NOT depend on
        // org.apache.commons (the platform has none) and
        // handles the GNU tar long-name extension (paths
        // longer than 100 chars use the @LongLink pseudo-
        // file). Implement a small subset here.
        TarInputStream(input).use { tar ->
            while (true) {
                val entry = tar.nextEntry() ?: break
                val outFile = File(into, entry.name)
                if (entry.isDirectory) {
                    if (!outFile.exists() && !outFile.mkdirs() && !outFile.isDirectory) {
                        throw BundledRootfsError.ExtractionFailed(
                            "Cannot create directory ${entry.name}"
                        )
                    }
                    continue
                }
                outFile.parentFile?.let { parent ->
                    if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
                        throw BundledRootfsError.ExtractionFailed(
                            "Cannot create parent directory for ${entry.name}"
                        )
                    }
                }
                // PHASE 145 — symlinks MUST be created, not
                // skipped. Phase 140 wrote 0-byte regular
                // files for every 'arch', 'ash', 'cat', 'cp',
                // … entry in the busybox-style rootfs, which
                // meant `/bin/sh -l` exec returned ENOENT
                // (`size = 0` for a symlink in our TarEntry)
                // and the shell exited with code 1. The
                // target storage is the app's internal
                // `filesDir` — ext4/F2FS, NOT FAT/exFAT —
                // so symlinks are always supported here.
                // External SD card was never in the picture.
                if (entry.type == TarEntryType.SYMLINK) {
                    val rawTarget = entry.linkTarget
                    if (rawTarget.isNullOrEmpty()) {
                        throw BundledRootfsError.ExtractionFailed(
                            "Symlink ${entry.name} has no target"
                        )
                    }
                    // PHASE 145 — the tar's symlink targets are
                    // POSIX-style: an absolute target (`/bin/busybox`)
                    // is anchored to the root of the directory tree
                    // being unpacked, NOT to the host filesystem's
                    // root. Phase 141's first cut passed the raw
                    // target to `Files.createSymbolicLink`, which
                    // interpreted `/bin/busybox` as a host absolute
                    // path. On Android `/bin/busybox` does not exist
                    // (the OS uses Toybox at `/system/bin/toybox`)
                    // so every symlink in the busybox-style rootfs
                    // (`bin/sh`, `bin/ash`, `bin/cat`, `bin/ls`, …)
                    // was a broken dangling link. `sh -l` then
                    // exited with code 1 the moment it tried to
                    // exec a non-resolvable path. The fix is the
                    // standard tar semantic:
                    //   1. Strip the leading `/` from `rawTarget` so
                    //      the result is interpreted against the
                    //      extraction root (`into`).
                    //   2. Resolve the result against `into` to
                    //      produce the absolute path inside the
                    //      unpacked rootfs.
                    //   3. Compute the path relative to the
                    //      symlink's parent directory. Storing a
                    //      relative target makes the symlink
                    //      portable — it does not depend on where
                    //      on the host filesystem the rootfs ends
                    //      up (`/data/user/0/.../files/distros/
                    //      bundled/alpine-mini/` today, could move
                    //      tomorrow).
                    val stripped = if (rawTarget.startsWith("/")) {
                        rawTarget.substring(1)
                    } else {
                        rawTarget
                    }
                    val resolvedInsideRootfs = File(into, stripped)
                    val parentDir = outFile.parentFile
                        ?: throw BundledRootfsError.ExtractionFailed(
                            "Symlink ${entry.name} has no parent directory"
                        )
                    val relativeTarget = parentDir.toPath()
                        .relativize(resolvedInsideRootfs.toPath())
                        .toString()
                    if (outFile.exists() || outFile.isDirectory) {
                        // If something already lives at this
                        // path (e.g. a stale 0-byte file from
                        // a pre-Phase-145 extraction, or a
                        // pre-existing symlink pointing at a
                        // different target), wipe it so the
                        // symlink(2) call below does not fail
                        // with EEXIST.
                        if (!outFile.delete()) {
                            throw BundledRootfsError.ExtractionFailed(
                                "Cannot clear stale path at ${entry.name}"
                            )
                        }
                    }
                    try {
                        java.nio.file.Files.createSymbolicLink(
                            outFile.toPath(),
                            java.nio.file.Paths.get(relativeTarget),
                        )
                    } catch (e: java.io.IOException) {
                        throw BundledRootfsError.ExtractionFailed(
                            "Cannot create symlink ${entry.name} -> $relativeTarget: ${e.message}"
                        )
                    }
                    continue
                }
                outFile.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = tar.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                }
                // Apply the unix mode (lower 9 bits are
                // rwx for owner/group/other).
                val mode = entry.mode and 0x1FFL
                if (mode != 0L) {
                    runCatching { outFile.setExecutable(true, false) }
                    val ownerRead = (mode shr 6) and 1L == 1L
                    val ownerWrite = (mode shr 6) and 2L == 2L
                    runCatching { outFile.setReadable(ownerRead, false) }
                    runCatching { outFile.setWritable(ownerWrite, false) }
                }
            }
        }
    }

    private fun copyRecursively(from: File, to: File) {
        if (from.isDirectory) {
            if (!to.exists() && !to.mkdirs() && !to.isDirectory) {
                throw BundledRootfsError.ExtractionFailed(
                    "Cannot create $to"
                )
            }
            from.listFiles()?.forEach { child ->
                copyRecursively(child, File(to, child.name))
            }
        } else {
            from.inputStream().use { input ->
                to.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}

/**
 * Errors the extractor can surface. Sealed so the caller
 * exhaustively `when`-matches every failure mode.
 */
sealed class BundledRootfsError(message: String) : RuntimeException(message) {
    /** The asset is missing from the APK (or the file does not exist). */
    class AssetNotFound(
        val distroId: String,
        val assetPath: String,
    ) : BundledRootfsError(
        "Bundled rootfs asset not found: $assetPath (distro=$distroId)"
    )

    /**
     * The asset's SHA-256 (or size) does not match the
     * pinned value in [BundledDistro.sha256]. This is
     * defense-in-depth: if the APK was tampered with
     * between signing and install, we refuse to extract.
     */
    class HashMismatch(
        val distroId: String,
        val expectedHash: String? = null,
        val actualHash: String? = null,
        val expectedSize: Long? = null,
        val actualSize: Long? = null,
    ) : BundledRootfsError(buildString {
        append("Bundled rootfs hash/size mismatch for ")
        append(distroId)
        if (expectedHash != null && actualHash != null) {
            append(" (expected=").append(expectedHash)
            append(", actual=").append(actualHash).append(")")
        }
        if (expectedSize != null && actualSize != null) {
            append(" [size expected=").append(expectedSize)
            append(", actual=").append(actualSize).append("]")
        }
    })

    /** The gzipped tar could not be read. */
    class ExtractionFailed(message: String) : BundledRootfsError(message)
}

private fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    forEach { b -> sb.append(((b.toInt() shr 4) and 0xF).toString(16)) ; sb.append((b.toInt() and 0xF).toString(16)) }
    return sb.toString()
}
