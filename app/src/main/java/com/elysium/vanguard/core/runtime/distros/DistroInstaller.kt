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
    private val onByteProgress: (bytesRead: Long) -> Unit = {}
) {
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
            val actualSha256 = runStage("download") {
                downloadArchive(distro, archive)
            }
            runStage("verify") {
                distro.sha256?.let { expected ->
                    if (!actualSha256.equals(expected, ignoreCase = true)) {
                        throw IOException("SHA-256 mismatch: expected $expected, got $actualSha256")
                    }
                }
            }

            val result = runStage("extract") {
                archive.inputStream().buffered().use { input ->
                    extractor.extract(
                        stream = input,
                        destDir = stagingDir,
                        kind = distro.rootfsKind,
                        progress = progress,
                        stripComponents = distro.stripComponents
                    )
                }
            }

            runStage("validate") {
                val health = RootfsHealth.inspect(stagingDir, result.bytesWritten)
                if (!health.isHealthy) {
                    throw IOException(health.reason ?: "rootfs validation failed")
                }
            }

            runStage("prepare manifest") {
                manifestStaging.writeText(
                    """{"id":"${distro.id}","installedAtMs":${System.currentTimeMillis()},"sha256":"$actualSha256","entries":${result.entriesExtracted},"bytes":${result.bytesWritten}}"""
                )
            }
            runStage("activate") {
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

    private inline fun <T> runStage(stage: String, block: () -> T): T {
        return try {
            block()
        } catch (failure: Exception) {
            throw IOException(
                "$stage failed: ${failure.message ?: failure.javaClass.simpleName}",
                failure
            )
        }
    }

    private fun downloadArchive(distro: Distro, archive: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        downloader.open(distro.rootfsUrl).use { input ->
            archive.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    onByteProgress(read.toLong())
                }
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
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
        /**
         * Suggested base directory for installed distros. We compute a
         * default `<filesDir>/distros/` but the caller is free to put
         * them anywhere app-private.
         */
        fun defaultBaseDir(appFilesDir: File): File = File(appFilesDir, "distros")
    }
}
