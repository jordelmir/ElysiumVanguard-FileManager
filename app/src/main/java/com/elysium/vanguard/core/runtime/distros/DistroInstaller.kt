package com.elysium.vanguard.core.runtime.distros

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * PHASE 9.6.2 — High-level installer: download a distro, extract, verify.
 *
 * Phase 9.6.3.3 — now accepts a per-byte progress callback so the UI
 * can show a real progress bar during the extract pass. The callback
 * fires once per [java.io.FilterInputStream] `read` call, not once
 * per entry.
 *
 * Phase 9.6.2 — first build; intentionally minimal.
 */
class DistroInstaller(
    private val downloader: DistroHttpDownloader,
    private val extractor: DistroRootfsExtractor = DistroRootfsExtractor(),
    /** Called every entry; safe to swap for [DistroRootfsExtractor.ProgressCallback.NONE]. */
    private val progress: DistroRootfsExtractor.ProgressCallback = DistroRootfsExtractor.ProgressCallback.NONE,
    /**
     * Called for every byte downloaded AND extracted. We surface the
     * same callback to both download and extract; consumers don't need
     * to distinguish (a progress bar doesn't care which side the
     * bytes are moving).
     */
    private val onByteProgress: (bytesRead: Long) -> Unit = {},
    /** Emits immutable phase snapshots for UI and diagnostic consumers. */
    private val onProgress: (DistroInstallProgress) -> Unit = {},
    private val maxDownloadAttempts: Int = DEFAULT_DOWNLOAD_ATTEMPTS,
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MS
) {
    init {
        require(maxDownloadAttempts > 0) { "maxDownloadAttempts must be positive" }
        require(retryDelayMillis >= 0L) { "retryDelayMillis cannot be negative" }
    }
    /**
     * Install [distro] into [baseDir]. Returns the rootfs directory.
     * The directory layout is `baseDir/<distro.id>/rootfs/` for the
     * extracted system and `baseDir/<distro.id>/install.error` when the
     * install fails.
     *
     * Atomicity:
     *   - Download into `<baseDir>/<id>/download.part`.
     *   - Verify the complete archive before extraction.
     *   - Extract and validate under `<id>/rootfs.staging/`.
     *   - Atomically replace `<id>/rootfs/` only after validation.
     *   - On failure: remove staging and retain a typed error sentinel.
     */
    @Throws(IOException::class)
    fun install(
        distro: Distro,
        baseDir: File
    ): File {
        val rootDir = File(baseDir, distro.id)
        val archive = File(rootDir, "download.part")
        val stagingDir = File(rootDir, "rootfs.staging")
        val rootfsDir = File(rootDir, "rootfs")
        val manifestFile = File(rootDir, "manifest.json")
        val manifestStaging = File(rootDir, "manifest.staging.json")
        val errorFile = File(rootDir, "install.error")
        if (!rootDir.isDirectory && !rootDir.mkdirs()) {
            throw IOException("Could not create ${rootDir.absolutePath}")
        }
        archive.delete()
        stagingDir.deleteRecursively()
        manifestStaging.delete()

        try {
            runStage(DistroInstallStage.PREFLIGHT) {
                ensureSufficientStorage(rootDir, distro)
            }
            val actualSha256 = runStage(DistroInstallStage.DOWNLOADING) {
                downloadArchiveWithRetry(distro, archive)
            }
            runStage(DistroInstallStage.VERIFYING) {
                distro.sha256?.let { expected ->
                    if (!actualSha256.equals(expected, ignoreCase = true)) {
                        throw IOException("SHA-256 mismatch: expected $expected, got $actualSha256")
                    }
                }
            }

            var extractedBytes = 0L
            val result = runStage(DistroInstallStage.EXTRACTING) {
                archive.inputStream().buffered().use { input ->
                    extractor.extract(
                        stream = input,
                        destDir = stagingDir,
                        kind = distro.rootfsKind,
                        progress = DistroRootfsExtractor.ProgressCallback { name, bytes ->
                            progress.onEntry(name, bytes)
                            extractedBytes += bytes
                            onByteProgress(bytes)
                            onProgress(DistroInstallProgress(DistroInstallStage.EXTRACTING, extractedBytes))
                        },
                        stripComponents = distro.stripComponents
                    )
                }
            }

            runStage(DistroInstallStage.VALIDATING) {
                val health = RootfsHealth.inspect(stagingDir, result.bytesWritten)
                if (!health.isHealthy) {
                    throw IOException(health.reason ?: "rootfs validation failed")
                }
            }

            runStage(DistroInstallStage.ACTIVATING) {
                manifestStaging.writeText(
                    """{"id":"${distro.id}","installedAtMs":${System.currentTimeMillis()},"sha256":"$actualSha256","entries":${result.entriesExtracted},"bytes":${result.bytesWritten}}"""
                )
            }
            runStage(DistroInstallStage.ACTIVATING) {
                activateAtomically(
                    stagingDir = stagingDir,
                    rootfsDir = rootfsDir,
                    manifestStaging = manifestStaging,
                    manifestFile = manifestFile,
                    errorFile = errorFile
                )
            }
            archive.delete()
            return rootfsDir
        } catch (failure: Exception) {
            stagingDir.deleteRecursively()
            archive.delete()
            manifestStaging.delete()
            val io = failure as? IOException ?: IOException(failure.message ?: "install failed", failure)
            writeError(rootDir, io.message ?: "install failed")
            throw io
        }
    }

    private inline fun <T> runStage(stage: DistroInstallStage, block: () -> T): T {
        onProgress(DistroInstallProgress(stage))
        return try {
            block()
        } catch (failure: Exception) {
            throw IOException(
                "${stage.label} failed: ${failure.message ?: failure.javaClass.simpleName}",
                failure
            )
        }
    }

    private fun downloadArchiveWithRetry(distro: Distro, archive: File): String {
        var lastFailure: IOException? = null
        for (attempt in 1..maxDownloadAttempts) {
            try {
                archive.delete()
                return downloadArchive(distro, archive, attempt)
            } catch (failure: IOException) {
                lastFailure = failure
                archive.delete()
                if (attempt == maxDownloadAttempts || !isRetryableDownloadFailure(failure)) break
                sleepBeforeRetry(attempt)
            }
        }
        throw IOException(
            "download exhausted $maxDownloadAttempts attempt(s): ${lastFailure?.message ?: "unknown failure"}",
            lastFailure
        )
    }

    private fun downloadArchive(distro: Distro, archive: File, attempt: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var downloadedBytes = 0L
        onProgress(DistroInstallProgress(DistroInstallStage.DOWNLOADING, attempt = attempt, maxAttempts = maxDownloadAttempts))
        downloader.open(distro.rootfsUrl).use { input ->
            archive.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    downloadedBytes += read
                    onByteProgress(read.toLong())
                    onProgress(
                        DistroInstallProgress(
                            stage = DistroInstallStage.DOWNLOADING,
                            bytesProcessed = downloadedBytes,
                            attempt = attempt,
                            maxAttempts = maxDownloadAttempts
                        )
                    )
                }
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun ensureSufficientStorage(rootDir: File, distro: Distro) {
        // The archive, a staging tree and the previous rootfs can coexist
        // during atomic activation. Reserve twice the catalog estimate plus
        // a small filesystem overhead rather than beginning a download that
        // will inevitably fail halfway through extraction.
        val required = (distro.approxSizeBytes.coerceAtLeast(MIN_ROOTFS_ESTIMATE) * 2L)
            .coerceAtMost(Long.MAX_VALUE - STORAGE_OVERHEAD_BYTES) + STORAGE_OVERHEAD_BYTES
        val available = rootDir.usableSpace
        if (available in 0 until required) {
            throw IOException(
                "insufficient free storage: need ${required.displayByteSize()}, " +
                    "available ${available.displayByteSize()}"
            )
        }
    }

    private fun isRetryableDownloadFailure(failure: IOException): Boolean {
        val message = failure.message.orEmpty()
        val status = Regex("HTTP (\\d{3})").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return status == null || status == 408 || status == 429 || status >= 500
    }

    private fun sleepBeforeRetry(attempt: Int) {
        val delay = retryDelayMillis * (1L shl (attempt - 1).coerceAtMost(4))
        if (delay == 0L) return
        try {
            Thread.sleep(delay)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("download interrupted before retry", interrupted)
        }
    }

    private fun activateAtomically(
        stagingDir: File,
        rootfsDir: File,
        manifestStaging: File,
        manifestFile: File,
        errorFile: File
    ) {
        val previousRootfs = File(rootfsDir.parentFile, "rootfs.previous")
        val previousManifest = File(rootfsDir.parentFile, "manifest.previous.json")
        previousRootfs.deleteRecursively()
        previousManifest.delete()
        if (rootfsDir.exists() && !rootfsDir.renameTo(previousRootfs)) {
            throw IOException("Could not preserve previous rootfs")
        }
        if (manifestFile.exists() && !manifestFile.renameTo(previousManifest)) {
            previousRootfs.renameTo(rootfsDir)
            throw IOException("Could not preserve previous manifest")
        }
        try {
            if (!stagingDir.renameTo(rootfsDir)) {
                throw IOException("Could not activate validated rootfs")
            }
            if (!manifestStaging.renameTo(manifestFile)) {
                throw IOException("Could not activate install manifest")
            }
            if (errorFile.exists() && !errorFile.delete()) {
                throw IOException("Could not clear previous install error")
            }
        } catch (failure: Exception) {
            rootfsDir.deleteRecursively()
            manifestFile.delete()
            if (previousRootfs.exists()) previousRootfs.renameTo(rootfsDir)
            if (previousManifest.exists()) previousManifest.renameTo(manifestFile)
            throw failure
        }
        previousRootfs.deleteRecursively()
        previousManifest.delete()
    }

    private fun writeError(rootDir: File, message: String) {
        rootDir.mkdirs()
        File(rootDir, "install.error").writeText(message + "\n")
    }

    companion object {
        private const val DEFAULT_DOWNLOAD_ATTEMPTS = 3
        private const val DEFAULT_RETRY_DELAY_MS = 350L
        private const val MIN_ROOTFS_ESTIMATE = 32L * 1024L * 1024L
        private const val STORAGE_OVERHEAD_BYTES = 64L * 1024L * 1024L
        /**
         * Suggested base directory for installed distros. We compute a
         * default `<filesDir>/distros/` but the caller is free to put
         * them anywhere app-private.
         */
        fun defaultBaseDir(appFilesDir: File): File = File(appFilesDir, "distros")
    }
}
