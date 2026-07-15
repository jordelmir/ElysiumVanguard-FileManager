package com.elysium.vanguard.core.runtime.distros.layer

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Phase 12.2 — journaled snapshot of an applied [SystemLayer].
 *
 * Master order §11.5: "snapshot before update; rollback restores
 * the previous SystemLayer". A snapshot is a flat copy of the
 * layer's directory (e.g. `elysium-layer-cli/`) into a staging
 * area under the rootfs's `_snapshots/` directory, plus a small
 * metadata file recording the layer id, version, and the SHA-256
 * of the tarball that produced the live layer at snapshot time.
 *
 * The metadata lets a future rollback detect "the live layer has
 * changed since the snapshot was taken" and refuse to roll back
 * over a newer version (master order §11.5: "protección contra
 * downgrade").
 *
 * Snapshots are NOT delta-based. A 50 MB layer produces a 50 MB
 * snapshot. That's fine for now — the SystemLayer is bounded by
 * the order's profiles (a few tens of MB at most). A future
 * phase can switch to a copy-on-write backend (overlayfs upper
 * dirs, reflink where the filesystem supports it) when the
 * snapshot size becomes a real cost.
 */
class SystemLayerSnapshot(
    private val rootfsDir: File
) {
    private val baseDir: File = File(rootfsDir, "_snapshots")
    private val liveLayerDir: File get() = File(rootfsDir, "elysium-layer-${lastLayerId}")

    /**
     * Take a snapshot of the layer [layerId]'s live directory,
     * recording the version the live layer was at and a content
     * fingerprint so rollback can detect downgrades. The snapshot
     * is stored at `<rootfs>/_snapshots/<id>@<version>/`.
     *
     * The [previousVersion] and [previousSha256] parameters describe
     * the live layer that is about to be replaced. They are NOT
     * extracted from the new [layer] — the caller already knows
     * which version it's about to overwrite. This separation keeps
     * the snapshot's data model independent of the [SystemLayer]
     * type (which requires a real file as its tarball).
     *
     * The operation is best-effort durable: the snapshot is staged
     * under `.tmp-<id>@<version>/` and atomically renamed to its
     * final name. A crashed snapshot leaves a `.tmp-*` directory
     * the next call sweeps up.
     */
    @Throws(IOException::class)
    fun take(
        layerId: String,
        previousVersion: String,
        previousSha256: String
    ): File {
        if (!baseDir.isDirectory && !baseDir.mkdirs()) {
            throw IOException("could not create snapshot dir: $baseDir")
        }
        val target = File(baseDir, "$layerId@$previousVersion")
        if (target.exists()) {
            // Idempotent: re-snapshotting the same id@version is
            // a no-op. Returning the existing snapshot is cheaper
            // than rebuilding it.
            return target
        }
        val staging = File(baseDir, ".tmp-$layerId@$previousVersion")
        if (staging.exists()) staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("could not create snapshot staging: $staging")

        val live = File(rootfsDir, "elysium-layer-$layerId")
        if (live.isDirectory) {
            copyRecursively(live, staging)
        }
        // Record the metadata that lets the rollback verify
        // we are not downgrading over a newer live version.
        File(staging, "META.json").writeText(
            """
            {"id":"$layerId","version":"$previousVersion","sha256":"$previousSha256"}
            """.trimIndent()
        )
        if (!staging.renameTo(target)) {
            throw IOException("could not promote snapshot staging to $target")
        }
        return target
    }

    /**
     * Restore the snapshot for [layerId] if one exists. Returns
     * the restored directory, or null if no snapshot is available.
     *
     * The [expectedLiveSha256] check is opt-in: pass a non-null
     * value to require the live layer's content to match the
     * snapshot's recorded fingerprint. The check guards against
     * rolling back over a layer the user has since re-applied
     * (a soft form of the "downgrade protection" from master
     * order §11.5; the strict form compares versions and lives
     * in [SystemLayerUpdater] as a separate concern).
     */
    fun restore(layerId: String, expectedLiveSha256: String?): File? {
        // Pick the most recent snapshot for this id. We treat
        // lexicographic order of "<id>@<version>" as a proxy for
        // recency; the version segment is conventionally
        // semver, which sorts correctly.
        val candidates = baseDir.listFiles { f -> f.isDirectory && f.name.startsWith("$layerId@") }
            ?: return null
        val snapshot = candidates.maxByOrNull { it.name } ?: return null
        val meta = File(snapshot, "META.json")
        if (meta.isFile && expectedLiveSha256 != null) {
            val recorded = Regex("\"sha256\":\"([0-9a-f]{64})\"")
                .find(meta.readText())
                ?.groupValues
                ?.get(1)
            if (recorded != null && recorded != expectedLiveSha256) {
                throw IOException(
                    "refusing to roll back $layerId: live sha256 " +
                        "($expectedLiveSha256) differs from snapshot ($recorded)"
                )
            }
        }
        val target = File(rootfsDir, "elysium-layer-$layerId")
        if (target.exists()) target.deleteRecursively()
        copyRecursively(snapshot, target)
        return target
    }

    /**
     * Drop snapshots older than [keepLatest] entries per layer id.
     * Called after a successful apply to bound the snapshot
     * directory's growth.
     */
    fun prune(keepLatest: Int) {
        if (!baseDir.isDirectory) return
        val byLayer = baseDir.listFiles { f -> f.isDirectory }
            ?.groupBy { f -> f.name.substringBefore('@') }
            ?: return
        for ((_, snapshots) in byLayer) {
            val ordered = snapshots.sortedByDescending { it.name }
            for (stale in ordered.drop(keepLatest)) {
                stale.deleteRecursively()
            }
        }
    }

    private fun copyRecursively(src: File, dst: File) {
        if (!src.exists()) return
        if (src.isDirectory) {
            if (!dst.exists() && !dst.mkdirs()) {
                throw IOException("could not create dir: $dst")
            }
            src.listFiles()?.forEach { child ->
                copyRecursively(child, File(dst, child.name))
            }
        } else {
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private var lastLayerId: String = ""
    fun bind(layerId: String) {
        lastLayerId = layerId
    }
}
