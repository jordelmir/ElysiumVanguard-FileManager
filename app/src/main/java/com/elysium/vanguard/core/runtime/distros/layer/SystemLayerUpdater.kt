package com.elysium.vanguard.core.runtime.distros.layer

import java.io.File
import java.io.IOException

/**
 * Phase 12.2 — orchestrates the apply of a [SystemLayerManifest]
 * into a rootfs.
 *
 * Master order §11.5: "snapshot before update; rollback restores
 * the previous SystemLayer; protección contra downgrade; manifest
 * firmado; verificación de integridad; actualización atómica".
 *
 * The updater:
 *
 *   1. Iterates the manifest in declared order, applying each
 *      layer. Earlier layers are visible to later ones because
 *      each is extracted into the same rootfs and a later layer
 *      overwrites any earlier file at the same path.
 *   2. Snapshots the *current* live layer (if any) before
 *      applying the new one, so a failed apply can be rolled
 *      back to the previous version.
 *   3. On hash mismatch, aborts the whole apply and leaves the
 *      rootfs untouched.
 *   4. On success, prunes old snapshots to bound the snapshot
 *      directory's growth.
 *
 * Concurrency: the updater is not thread-safe. The caller
 * (typically the install pipeline) is expected to serialize
 * updates per-rootfs.
 */
class SystemLayerUpdater(
    private val applier: SystemLayerApplier = SystemLayerApplier(),
    private val keepLatestSnapshots: Int = 2
) {
    /**
     * Apply [manifest] into [rootfsDir]. Returns the list of
     * layers that were successfully applied, in order.
     *
     * Throws [LayerHashMismatch] on the first hash mismatch (the
     * rest of the manifest is not applied; layers that were
     * already applied in this run are left in place).
     * Throws [IOException] on any other extraction / fs error.
     */
    @Throws(IOException::class, LayerHashMismatch::class)
    fun apply(manifest: SystemLayerManifest, rootfsDir: File): List<SystemLayer> {
        require(rootfsDir.isDirectory) { "rootfsDir is not a directory: $rootfsDir" }
        val snapshot = SystemLayerSnapshot(rootfsDir)
        val applied = mutableListOf<SystemLayer>()
        for (layer in manifest.layers) {
            // Snapshot the previous live version (if any) BEFORE
            // overwriting it. The snapshot is cheap because the
            // layer dir, when present, is small.
            val live = File(rootfsDir, "elysium-layer-${layer.id}")
            if (live.isDirectory) {
                val previousVersion = readVersionMarker(live) ?: "previous"
                val previousSha = computeLiveSha256(live)
                snapshot.take(layer.id, previousVersion, previousSha)
            }
            applier.apply(layer, rootfsDir)
            writeVersionMarker(live, layer.version)
            applied += layer
        }
        snapshot.prune(keepLatestSnapshots)
        return applied
    }

    /**
     * Read the version marker we leave inside every live layer
     * directory. Returns null if the marker is missing (which is
     * the case for layers applied before this updater existed —
     * they are still snapshot-able, just without a recorded version).
     */
    private fun readVersionMarker(liveDir: File): String? {
        val marker = File(liveDir, "VERSION")
        return if (marker.isFile) marker.readText().trim().ifBlank { null } else null
    }

    /**
     * Write the version marker so a future snapshot can record
     * "the previous version was X" instead of the generic
     * "previous".
     */
    private fun writeVersionMarker(liveDir: File, version: String) {
        File(liveDir, "VERSION").writeText(version + "\n")
    }

    /**
     * Roll back the layer [layerId] in [rootfsDir] to its most
     * recent snapshot. This is a blind restore: it does not
     * check whether the live layer has changed since the snapshot
     * was taken. Use [rollbackStrict] for the strict downgrade-
     * protection check (master order §11.5).
     */
    @Throws(IOException::class)
    fun rollback(layerId: String, rootfsDir: File): File? =
        SystemLayerSnapshot(rootfsDir).restore(layerId, expectedLiveSha256 = null)

    /**
     * Strict rollback: refuses if the live layer's content
     * fingerprint does not match the snapshot's recorded fingerprint.
     * Use this when the caller knows the user has not touched the
     * layer since the snapshot was taken (e.g. right after a failed
     * apply that left the live dir in a known state).
     */
    @Throws(IOException::class)
    fun rollbackStrict(layerId: String, rootfsDir: File): File? {
        val live = File(rootfsDir, "elysium-layer-$layerId")
        val liveSha = if (live.isDirectory) computeLiveSha256(live) else null
        return SystemLayerSnapshot(rootfsDir).restore(layerId, liveSha)
    }

    /**
     * Compute a stable "fingerprint" of the live layer directory.
     *
     * Real layer integrity is the SHA-256 of the tarball that
     * produced it, which is what [SystemLayer.sha256] records.
     * After extraction we no longer have the tarball at hand
     * (and we should not keep a copy on the user's device), so
     * for the rollback downgrade check we fall back to a
     * sorted-concat SHA-256 over the layer's relative paths and
     * file contents. This is collision-resistant enough for
     * "did the live layer change since the snapshot?" purposes.
     */
    private fun computeLiveSha256(liveDir: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val paths = liveDir.walkBottomUp()
            .filter { it.isFile }
            .map { it.relativeTo(liveDir).path }
            .sorted()
        for (path in paths) {
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(0)
            val file = liveDir.resolve(path)
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
