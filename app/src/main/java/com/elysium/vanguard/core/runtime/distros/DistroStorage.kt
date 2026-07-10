package com.elysium.vanguard.core.runtime.distros

import java.io.File

/**
 * PHASE 9.6.2 — Snapshot of an installed distro on disk.
 *
 * Built by `DistroStorage.listInstalled()` from the on-disk layout:
 *
 *     <baseDir>/
 *       └── <distro-id>/
 *           ├── manifest.json    (always present after install)
 *           ├── rootfs/          (extracted system)
 *           └── install.error    (only if last install failed)
 *
 * `DistroStorage` does NOT talk to Room. Phase 9.6.3 introduces a Room
 * entity for "which distros the user opened most recently", but this
 * snapshot is the source-of-truth for what exists on the device.
 *
 * Phase 9.6.2 — first build; intentionally minimal.
 */
data class DistroInstallation(
    val distro: Distro,
    val rootDir: File,
    val rootfsDir: File,
    val installedAtEpochMs: Long?,
    val sizeOnDiskBytes: Long,
    val lastError: String?
) {
    val isHealthy: Boolean get() = lastError == null && rootfsDir.isDirectory
}

/**
 * PHASE 9.6.2 — Read-only view over the installed distros on disk.
 *
 * Why we don't use Room for 9.6.2: the file system IS the database. Each
 * install writes a manifest.json we can re-parse cheaply on every UI
 * rebuild. Room goes in 9.6.3 when we want to track "recently opened"
 * across app restarts.
 *
 * Phase 9.6.3.3 — extended to also surface custom rootfs (sentinel file
 * `installed-via=custom` plus a `manifest.json` with the install-time
 * metadata). Catalog entries are still resolved the same way; for
 * custom entries the parser back-fills a [Distro] from the manifest.
 *
 * Phase 9.6.2 — first build; intentionally minimal.
 */
class DistroStorage(
    private val baseDir: File,
    /**
     * Parser for custom rootfs manifests. Defaults to a fresh
     * [CustomManifestParser]; injected by Hilt in production.
     */
    private val customManifestParser: CustomManifestParser = CustomManifestParser()
) {

    /**
     * Inspect [baseDir] and return one [DistroInstallation] per known
     * distro id present on disk. Catalog entries come from
     * [DistroCatalog.find]; custom entries are rebuilt from their
     * `manifest.json` (Phase 9.6.3.3).
     */
    fun listInstalled(): List<DistroInstallation> {
        if (!baseDir.isDirectory) return emptyList()
        val out = ArrayList<DistroInstallation>()
        for (child in baseDir.listFiles().orEmpty()) {
            if (!child.isDirectory) continue
            val distro = lookupDistro(child) ?: continue
            val rootfsDir = File(child, "rootfs")
            val manifest = File(child, "manifest.json")
            val errFile = File(child, "install.error")
            val installedAt = if (manifest.exists()) {
                parseInstalledAt(manifest) ?: run {
                    manifest.lastModified()
                }
            } else null
            val size = if (rootfsDir.isDirectory) {
                rootfsDir.walkTopDown().sumOf { f ->
                    if (f.isFile) f.length() else 0L
                }
            } else 0L
            val err = if (errFile.exists()) errFile.readText().trim() else null
            out += DistroInstallation(
                distro = distro,
                rootDir = child,
                rootfsDir = rootfsDir,
                installedAtEpochMs = installedAt,
                sizeOnDiskBytes = size,
                lastError = err
            )
        }
        return out
    }

    /**
     * PHASE 9.6.3.3 — Resolve the distro metadata for a child directory
     * under the base. Catalog ids go through [DistroCatalog.find];
     * everything else gets back-filled from its `manifest.json`.
     */
    private fun lookupDistro(child: File): Distro? {
        DistroCatalog.find(child.name)?.let { return it }
        val manifest = File(child, "manifest.json")
        return customManifestParser.parse(manifest)
    }

    fun findInstalled(id: String): DistroInstallation? =
        listInstalled().firstOrNull { it.distro.id == id }

    /** Remove everything under `baseDir/<id>/`. */
    fun remove(id: String): Boolean {
        val target = File(baseDir, id)
        return target.deleteRecursively()
    }

    private fun parseInstalledAt(manifest: File): Long? {
        // Phase 9.6.2 manifest is intentionally tiny; we read it as plain
        // text and regex out the timestamp. Phase 9.6.3 swaps this for a
        // proper JSON parser when we add fields.
        return try {
            val text = manifest.readText()
            val match = Regex("\"installedAtMs\":(\\d+)").find(text)
            match?.groupValues?.get(1)?.toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }
}
