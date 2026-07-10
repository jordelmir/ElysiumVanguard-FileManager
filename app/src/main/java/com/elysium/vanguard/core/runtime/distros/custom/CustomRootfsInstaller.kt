package com.elysium.vanguard.core.runtime.distros.custom

import com.elysium.vanguard.core.runtime.distros.Distro
import com.elysium.vanguard.core.runtime.distros.DistroHttpDownloader
import com.elysium.vanguard.core.runtime.distros.DistroRootfsExtractor
import com.elysium.vanguard.core.runtime.distros.DistroStorage
import com.elysium.vanguard.core.runtime.distros.ProgressInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream

/**
 * PHASE 9.6.3.2 — Installer for a custom rootfs URL.
 *
 * The pipeline is:
 *
 *   1. Open the URL via [DistroHttpDownloader.open] (real HTTPS fetch).
 *   2. Wrap the resulting byte stream in the right decompressor
 *      (gzip, xz, bzip2) based on [CustomRootfsKind].
 *   3. Hand the uncompressed tar stream to [DistroRootfsExtractor].
 *   4. Write a manifest in `<baseDir>/<id>/manifest.json`.
 *   5. Touch a sentinel file (`<baseDir>/<id>/installed-via=custom`).
 *
 * The result mirrors what Phase 9.6.2's [com.elysium.vanguard.core.runtime.distros.DistroInstaller]
 * did for catalog distros. We keep this code separate because:
 *
 *   - The download is custom (any URL, not just the catalog's).
 *   - The compression detection is `CustomRootfsKind`-driven, not `RootfsKind`.
 *   - The metadata we record has to mention the source URL so the user
 *     can later audit "what did I install this distro from?".
 *
 * Phase 9.6.3.2 — first build; intentionally minimal.
 */
class CustomRootfsInstaller(
    private val downloader: DistroHttpDownloader,
    private val extractor: DistroRootfsExtractor = DistroRootfsExtractor()
) {

    /**
     * Install a custom rootfs from [url] under [baseDir] using the
     * supplied [distro] metadata. The distro id is the directory name
     * under baseDir.
     *
     * @param onProgress optional per-entry progress callback. Fires per
     *   tar entry.
     * @param onByteProgress optional per-byte progress callback (9.6.3.3).
     *   Fires once per byte downloaded + decompressed + extracted.
     */
    @Throws(IOException::class)
    fun install(
        distro: Distro,
        baseDir: File,
        url: String,
        kind: CustomRootfsKind,
        onProgress: (entriesExtracted: Int, lastEntryName: String) -> Unit = { _, _ -> },
        onByteProgress: (bytesRead: Long) -> Unit = {}
    ): Result<File> {
        val targetDir = File(baseDir, distro.id)
        if (targetDir.exists()) {
            return Result.failure(IOException("Distro already installed: ${distro.id}"))
        }
        if (!targetDir.mkdirs()) {
            return Result.failure(IOException("Could not create $targetDir"))
        }
        val rootfsDir = File(targetDir, "rootfs")
        if (!rootfsDir.mkdirs()) {
            targetDir.deleteRecursively()
            return Result.failure(IOException("Could not create $rootfsDir"))
        }
        val manifest = File(targetDir, "manifest.json")
        val installedVia = File(targetDir, "installed-via=custom")
        return try {
            val stream = downloader.open(url)
            // Phase 9.6.3.3 — wrap with ProgressInputStream first so bytes
            // are counted regardless of the compression wrapper that comes
            // next. The counting stream wraps the entire download.
            val counting = ProgressInputStream(stream, onByteProgress)
            val decompressed = decompressingStream(counting, kind)
            // We intentionally do not use use{} here because the
            // decompression streams wrap the underlying connection and
            // we want the order: close decompressor first (drains
            // trailing tar data), then close the outer HTTPS stream.
            val result = try {
                extractor.extractRawTar(
                    stream = decompressed,
                    destDir = rootfsDir,
                    progress = object : DistroRootfsExtractor.ProgressCallback {
                        override fun onEntry(name: String, bytes: Long) {
                            onProgress(1, name)
                        }
                    }
                )
            } finally {
                runCatching { decompressed.close() }
            }
            writeManifest(
                manifest = manifest,
                distro = distro,
                url = url,
                kind = kind,
                bytesWritten = result.bytesWritten,
                entries = result.entriesExtracted,
                bytesDownloaded = counting.progressBytes
            )
            installedVia.createNewFile()
            Result.success(rootfsDir)
        } catch (io: IOException) {
            targetDir.deleteRecursively()
            Result.failure(io)
        }
    }

    private fun decompressingStream(input: InputStream, kind: CustomRootfsKind): InputStream {
        return when (kind) {
            CustomRootfsKind.TarGz, CustomRootfsKind.Tgz -> GZIPInputStream(input)
            CustomRootfsKind.TarXz -> XZCompressorInputStream(input)
            CustomRootfsKind.Tar -> input
            CustomRootfsKind.Unknown -> throw IOException(
                "Cannot extract unknown kind"
            )
        }
    }

    private fun writeManifest(
        manifest: File,
        distro: Distro,
        url: String,
        kind: CustomRootfsKind,
        bytesWritten: Long,
        entries: Int,
        bytesDownloaded: Long
    ) {
        // Hand-rolled JSON keeps the file foot-gun-free and doesn't
        // require pulling in a second JSON library; the existing
        // catalog installer uses the same hand-rolled approach.
        val text = buildString {
            append('{')
            append("\"distroId\":\"").append(jsonEscape(distro.id)).append("\",")
            append("\"displayName\":\"").append(jsonEscape(distro.displayName)).append("\",")
            append("\"version\":\"").append(jsonEscape(distro.version)).append("\",")
            append("\"packageManager\":\"").append(jsonEscape(distro.packageManager)).append("\",")
            append("\"rootfsUrl\":\"").append(jsonEscape(url)).append("\",")
            append("\"rootfsKind\":\"").append(jsonEscape(kind.name)).append("\",")
            append("\"installedAtMs\":").append(System.currentTimeMillis()).append(',')
            append("\"installVia\":\"custom\",")
            append("\"bytesWritten\":").append(bytesWritten).append(',')
            append("\"bytesDownloaded\":").append(bytesDownloaded).append(',')
            append("\"entriesExtracted\":").append(entries)
            append('}')
        }
        manifest.writeText(text)
    }

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}

/**
 * Convenience for callers that only know the URL: runs the validator
 * then the installer in one call. Returns a [Result] describing the
 * outcome; the caller can decide whether to surface the error.
 *
 * Phase 9.6.3.2 — first build; intentionally minimal.
 */
class CustomRootfsPipeline(
    private val validator: CustomRootfsValidator,
    private val installer: CustomRootfsInstaller
) {
    /**
     * Validate the URL; if it's acceptable, run the installer.
     * Caller has already constructed the [Distro] metadata (id,
     * displayName, version, packageManager).
     */
    @Throws(IOException::class)
    fun install(
        distro: Distro,
        baseDir: File,
        url: String,
        onProgress: (entriesExtracted: Int, lastEntryName: String) -> Unit = { _, _ -> },
        onByteProgress: (bytesRead: Long) -> Unit = {}
    ): Result<File> {
        val probe = validator.probe(url)
        if (!probe.isAcceptable) {
            return Result.failure(IOException("URL not acceptable: $probe"))
        }
        return installer.install(
            distro = distro.copy(rootfsUrl = url),
            baseDir = baseDir,
            url = url,
            kind = probe.suggestedKind,
            onProgress = onProgress,
            onByteProgress = onByteProgress
        )
    }

    /**
     * Tells whether a previously-installed distro lives under
     * `<baseDir>/<id>/installed-via=custom` so the UI can group custom
     * vs. catalog entries in the runtime screen.
     */
    fun isCustom(baseDir: File, id: String): Boolean {
        val sentinel = File(File(baseDir, id), "installed-via=custom")
        return sentinel.exists()
    }

    /**
     * Companion accessor: list IDs in [baseDir] that have the custom
     * sentinel. Used by the UI to render a "Custom" section without
     * leaking implementation details.
     */
    fun listCustomIds(baseDir: File): List<String> {
        if (!baseDir.isDirectory) return emptyList()
        return baseDir.listFiles()
            ?.filter { it.isDirectory && File(it, "installed-via=custom").exists() }
            ?.map { it.name }
            ?: emptyList()
    }
}
