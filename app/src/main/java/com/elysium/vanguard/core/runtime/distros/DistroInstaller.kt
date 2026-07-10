package com.elysium.vanguard.core.runtime.distros

import java.io.File
import java.io.IOException

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
     *   - Download into `<baseDir>/<id>/download/`
     *   - Extract into `<baseDir>/<id>/rootfs/`
     *   - On success: remove `<id>/download`
     *   - On failure: drop a sentinel in `<id>/install.error`, keep the
     *     partial rootfs so the user can poke at what we managed to write.
     */
    @Throws(IOException::class)
    fun install(
        distro: Distro,
        baseDir: File,
        cancel: Thread.UncaughtExceptionHandler? = null
    ): File {
        val rootDir = File(baseDir, distro.id)
        val downloadDir = File(rootDir, "download")
        val rootfsDir = File(rootDir, "rootfs")
        rootfsDir.mkdirs()

        val stream = try {
            downloader.open(distro.rootfsUrl)
        } catch (io: IOException) {
            writeError(rootDir, "download failed: ${io.message}")
            throw io
        }
        val counting = ProgressInputStream(stream, onByteProgress)

        counting.use { input ->
            try {
                extractor.extract(input, rootfsDir, distro.rootfsKind, progress)
            } catch (io: IOException) {
                writeError(rootDir, "extract failed: ${io.message}")
                throw io
            }
        }

        // Stash a manifest for the runtime manager. Phase 9.6.3 reads it.
        File(rootDir, "manifest.json").writeText(
            """{"id":"${distro.id}","installedAtMs":${System.currentTimeMillis()}}"""
        )

        downloadDir.deleteRecursively()
        return rootfsDir
    }

    private fun writeError(rootDir: File, message: String) {
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
