package com.elysium.vanguard.core.runtime.distros.custom

import com.elysium.vanguard.core.runtime.distros.Distro
import com.elysium.vanguard.core.runtime.distros.DistroHttpDownloader
import com.elysium.vanguard.core.runtime.distros.DistroRootfsExtractor
import com.elysium.vanguard.core.runtime.distros.DistroStorage
import com.elysium.vanguard.core.runtime.distros.RootfsHealth
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
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
        if (!baseDir.isDirectory && !baseDir.mkdirs()) {
            return Result.failure(IOException("Could not create $baseDir"))
        }
        val targetDir = File(baseDir, distro.id)
        if (targetDir.exists()) {
            return Result.failure(IOException("Distro already installed: ${distro.id}"))
        }
        val stagingDir = File(baseDir, ".${distro.id}.installing")
        stagingDir.deleteRecursively()
        if (!stagingDir.mkdirs()) {
            return Result.failure(IOException("Could not create $stagingDir"))
        }
        val archive = File(stagingDir, "download.part")
        val rootfsDir = File(stagingDir, "rootfs")
        if (!rootfsDir.mkdirs()) {
            stagingDir.deleteRecursively()
            return Result.failure(IOException("Could not create $rootfsDir"))
        }
        val manifest = File(stagingDir, "manifest.json")
        val installedVia = File(stagingDir, "installed-via=custom")
        return try {
            val receipt = downloadArchive(url, archive, onByteProgress)
            var entriesSeen = 0
            val result = archive.inputStream().buffered().use { compressed ->
                decompressingStream(compressed, kind).use { tar ->
                    extractor.extractRawTar(
                        stream = tar,
                        destDir = rootfsDir,
                        progress = DistroRootfsExtractor.ProgressCallback { name, _ ->
                            entriesSeen += 1
                            onProgress(entriesSeen, name.removeSuffix("/"))
                        }
                    )
                }
            }
            val health = RootfsHealth.inspect(rootfsDir, result.bytesWritten)
            if (!health.isHealthy) {
                throw IOException(health.reason ?: "custom rootfs validation failed")
            }
            if (!archive.delete()) throw IOException("Could not remove temporary archive")
            writeManifest(
                manifest = manifest,
                distro = distro,
                url = url,
                kind = kind,
                bytesWritten = result.bytesWritten,
                entries = result.entriesExtracted,
                bytesDownloaded = receipt.bytesDownloaded,
                sha256 = receipt.sha256
            )
            if (!installedVia.createNewFile()) throw IOException("Could not write custom install sentinel")
            if (!stagingDir.renameTo(targetDir)) throw IOException("Could not activate custom rootfs")
            Result.success(File(targetDir, "rootfs"))
        } catch (failure: Exception) {
            stagingDir.deleteRecursively()
            Result.failure(failure as? IOException ?: IOException(failure.message ?: "custom install failed", failure))
        }
    }

    private fun downloadArchive(
        url: String,
        archive: File,
        onByteProgress: (bytesRead: Long) -> Unit
    ): DownloadReceipt {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        downloader.open(url).use { input ->
            archive.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    if (total > MAX_ARCHIVE_BYTES) {
                        throw IOException("Custom rootfs archive exceeds 2 GB safety limit")
                    }
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    onByteProgress(read.toLong())
                }
            }
        }
        val sha256 = digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
        return DownloadReceipt(total, sha256)
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
        bytesDownloaded: Long,
        sha256: String
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
            append("\"sha256\":\"").append(sha256).append("\",")
            append("\"entriesExtracted\":").append(entries)
            append('}')
        }
        manifest.writeText(text)
    }

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private data class DownloadReceipt(
        val bytesDownloaded: Long,
        val sha256: String
    )

    private companion object {
        const val MAX_ARCHIVE_BYTES = 2L * 1024L * 1024L * 1024L
    }
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
