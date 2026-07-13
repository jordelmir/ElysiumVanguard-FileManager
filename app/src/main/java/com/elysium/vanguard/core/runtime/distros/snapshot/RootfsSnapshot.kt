package com.elysium.vanguard.core.runtime.distros.snapshot

import java.io.File
import java.nio.file.Files as NioFiles

/**
 * PHASE 9.6.3.1 — Snapshot of an installed distro.
 *
 * A snapshot is a *copy* of an existing rootfs, named with a timestamp
 * suffix, intended for safe experimentation (e.g. trying `apt upgrade`
 * or testing a custom rc script). Snapshots live under the same base
 * dir as their source; the installer doesn't know they're related.
 *
 * Implementation strategy: because we don't have bind mounts yet
 * (Phase 9.6.3.1 lands the bridge descriptors but the jailed shell
 * doesn't mount them), we do an honest recursive copy. For Alpine +
 * Debian-mini that's 60-400 MB of `cp -r` work; for Arch (~350 MB) it's
 * still within the budget for an on-demand action. Future phases
 * (9.6.4+) will switch to a hard-link-based snapshot using `linkat`
 * when 9.6.3.1.1 vendors the link extension.
 *
 * Phase 9.6.3.1 — first build; intentionally minimal.
 */
data class DistroSnapshot(
    /** The source rootfs we copied from. */
    val sourceId: String,
    /** The snapshot id, e.g. "alpine-latest@2026-07-09T1612Z". */
    val id: String,
    /** On-disk location of the snapshot rootfs. */
    val rootfsDir: File,
    /** Bytes copied (sentinel: -1 if the snapshot failed mid-copy). */
    val bytesCopied: Long
) {
    val isComplete: Boolean get() = bytesCopied >= 0 && rootfsDir.isDirectory
}

object SnapshotIds {
    /**
     * Build a deterministic snapshot id from a source id and a current
     * clock reading. The format is sortable lexicographically so the
     * UI can list snapshots newest-first with a simple `sortedDescending`.
     */
    fun next(sourceId: String, epochMs: Long = System.currentTimeMillis()): String {
        val ts = java.time.Instant.ofEpochMilli(epochMs)
            .toString()
            .replace(":", "")
            .replace(".", "-")
        return "$sourceId@$ts"
    }
}

/**
 * PHASE 9.6.3.1 — Snapshot creator.
 *
 * Usage:
 *
 *   val creator = RootfsSnapshot(baseDir = File("/data/.../files/distros"))
 *   val result = creator.capture(
 *       sourceId = "alpine-latest",
 *       onProgress = { files, bytes -> ... }
 *   )
 *
 * Errors raise an [IOException] with a useful message; partial
 * directories are deleted on failure to avoid leaving phantom
 * snapshots.
 *
 * Phase 9.6.3.1 — first build; intentionally minimal.
 */
class RootfsSnapshot(
    private val baseDir: File
) {
    /**
     * Copy the on-disk rootfs at `<baseDir>/<sourceId>/rootfs/` into
     * `<baseDir>/<id>/rootfs/` where `id = sourceId@<timestamp>`.
     */
    @Throws(java.io.IOException::class)
    fun capture(
        sourceId: String,
        onProgress: ((filesCopied: Int, bytesCopied: Long) -> Unit)? = null
    ): DistroSnapshot {
        val srcDir = File(File(baseDir, sourceId), "rootfs")
        require(srcDir.isDirectory) { "source rootfs not found: $srcDir" }
        val id = SnapshotIds.next(sourceId)
        val destBase = File(baseDir, id)
        if (destBase.exists()) {
            throw java.io.IOException("snapshot already exists: $id")
        }
        if (!destBase.mkdirs()) {
            throw java.io.IOException("could not create $destBase")
        }
        val destDir = File(destBase, "rootfs")
        try {
            val total = copyTree(srcDir, destDir, onProgress)
            return DistroSnapshot(
                sourceId = sourceId,
                id = id,
                rootfsDir = destDir,
                bytesCopied = total
            )
        } catch (io: java.io.IOException) {
            destBase.deleteRecursively()
            throw io
        }
    }

    /**
     * Delete a snapshot by id. The id here is the full "<sourceId>@<ts>"
     * string used as the directory name under [baseDir].
     */
    fun remove(snapshotId: String): Boolean {
        val dir = File(baseDir, snapshotId)
        return dir.deleteRecursively()
    }

    /**
     * Restore a complete snapshot into its original distro rootfs using a
     * staged copy and directory swap. If staging fails, the live rootfs is
     * untouched; if the swap fails, the previous rootfs is restored.
     */
    @Throws(java.io.IOException::class)
    fun restore(snapshotId: String): DistroSnapshot {
        require(SNAPSHOT_ID.matches(snapshotId)) { "invalid snapshot id" }
        val sourceId = snapshotId.substringBefore('@')
        val snapshotRoot = File(File(baseDir, snapshotId), "rootfs")
        require(snapshotRoot.isDirectory) { "snapshot rootfs not found: $snapshotId" }
        val liveParent = File(baseDir, sourceId)
        val liveRoot = File(liveParent, "rootfs")
        require(liveRoot.isDirectory) { "live rootfs not found: $sourceId" }

        val stage = File(liveParent, ".restore-${java.util.UUID.randomUUID()}")
        val backup = File(liveParent, ".rootfs-before-restore-${java.util.UUID.randomUUID()}")
        try {
            val bytes = copyTree(snapshotRoot, stage, onProgress = null)
            if (!liveRoot.renameTo(backup)) throw java.io.IOException("could not stage the current rootfs")
            if (!stage.renameTo(liveRoot)) {
                backup.renameTo(liveRoot)
                throw java.io.IOException("could not activate restored rootfs")
            }
            backup.deleteRecursively()
            return DistroSnapshot(
                sourceId = sourceId,
                id = snapshotId,
                rootfsDir = liveRoot,
                bytesCopied = bytes
            )
        } catch (error: Exception) {
            stage.deleteRecursively()
            if (!liveRoot.exists() && backup.exists()) backup.renameTo(liveRoot)
            if (error is java.io.IOException) throw error
            throw java.io.IOException("snapshot restore failed", error)
        }
    }

    /**
     * Enumerate all snapshots under [baseDir]. A "snapshot" is any
     * directory whose name matches the [SnapshotIds.next] pattern and
     * which is not in the live catalog (`DistroCatalog.ALL` ids).
     */
    fun list(): List<DistroSnapshot> {
        if (!baseDir.isDirectory) return emptyList()
        val out = ArrayList<DistroSnapshot>()
        baseDir.listFiles()?.forEach { child ->
            if (!child.isDirectory) return@forEach
            val name = child.name
            if (!name.contains('@')) return@forEach
            val sourceId = name.substringBefore('@')
            val rootfsDir = File(child, "rootfs")
            if (!rootfsDir.isDirectory) return@forEach
            val bytes = rootfsDir.walkTopDown().sumOf { f ->
                if (f.isFile) f.length() else 0L
            }
            out += DistroSnapshot(
                sourceId = sourceId,
                id = name,
                rootfsDir = rootfsDir,
                bytesCopied = bytes
            )
        }
        return out.sortedByDescending { it.id }
    }

    private fun copyTree(
        src: File,
        dst: File,
        onProgress: ((Int, Long) -> Unit)?
    ): Long {
        if (!dst.exists() && !dst.mkdirs()) {
            throw java.io.IOException("cannot create $dst")
        }
        var total = 0L
        var count = 0
        src.listFiles()?.forEach { child ->
            when {
                child.isDirectory -> {
                    total += copyTree(child, File(dst, child.name), onProgress)
                    if (onProgress != null) onProgress(count, total)
                }
                NioFiles.isSymbolicLink(child.toPath()) -> {
                    val linkTarget = NioFiles.readSymbolicLink(child.toPath())
                    val target = File(dst, child.name)
                    try {
                        NioFiles.createSymbolicLink(target.toPath(), linkTarget)
                    } catch (_: UnsupportedOperationException) {
                        // Some FS don't allow symlinks; fall back to a sentinel file.
                        target.writeText("symlink→${linkTarget.toString()}")
                    }
                }
                else -> {
                    val target = File(dst, child.name)
                    child.inputStream().use { input ->
                        target.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    total += child.length()
                    count += 1
                    if (onProgress != null) onProgress(count, total)
                }
            }
        }
        return total
    }

    private companion object {
        // Reserved for future caches; the symbols shadow global NIO so
        // we don't accidentally call them as if they were instance
        // methods.
        val SNAPSHOT_ID = Regex("[A-Za-z0-9._-]+@[A-Za-z0-9._:-]+")
    }
}
