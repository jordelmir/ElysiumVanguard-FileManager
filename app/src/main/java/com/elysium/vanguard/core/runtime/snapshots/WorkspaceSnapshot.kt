package com.elysium.vanguard.core.runtime.snapshots

import com.elysium.vanguard.core.runtime.bridge.MountEntry

/**
 * Phase 49 — an immutable record of a captured
 * workspace state.
 *
 * A snapshot is *not* just the rootfs files at the
 * moment of capture. It is the rootfs files **and**
 * the bind mounts **and** the env vars **and** the
 * resource limits that were in effect when the
 * snapshot was taken. A rollback that restores the
 * rootfs but leaves the live session with a
 * different mount plan would be incoherent — a
 * write that should have been confined to the
 * snapshotted mount would silently end up on the
 * live one.
 *
 * The snapshot is the unit the runtime rolls back
 * to. The snapshot's [id] is a globally unique
 * string (`snap-<systemTimeMs>-<counter>`); the
 * [workspaceId] is the owning workspace; the
 * [rootfsPath] is the absolute path to the
 * snapshot's copy of the rootfs; the
 * [mountPlan.mounts] is the [MountEntry] list that
 * was active at snapshot time.
 *
 * [sizeBytes] is the on-disk size of the snapshot's
 * rootfs at capture time. Zero means "unknown"
 * (the engine did not compute it; e.g. the
 * hardlink strategy makes the size ambiguous
 * because the snapshot shares inodes with the
 * source).
 *
 * [copyStrategy] records which copy strategy the
 * engine used to capture the snapshot. A future
 * "snapshot GC" job can use this to decide which
 * snapshots are cheap to keep (hardlink) vs
 * expensive (full copy).
 */
data class WorkspaceSnapshot(
    val id: String,
    val workspaceId: String,
    val label: String,
    val createdAtMs: Long,
    val rootfsPath: String,
    val mountPlan: MountPlan,
    val sizeBytes: Long,
    val copyStrategy: CopyStrategy
) {
    init {
        require(id.isNotBlank()) { "snapshot id must not be blank" }
        require(workspaceId.isNotBlank()) { "workspaceId must not be blank" }
        require(label.isNotBlank()) { "snapshot label must not be blank" }
        require(rootfsPath.isNotBlank()) { "rootfsPath must not be blank" }
        require(sizeBytes >= 0) { "sizeBytes must be non-negative" }
    }

    override fun toString(): String =
        "WorkspaceSnapshot(id=$id, workspaceId=$workspaceId, " +
            "label='$label', copyStrategy=$copyStrategy, sizeBytes=$sizeBytes)"
}

/**
 * The copy strategy the engine used to capture the
 * snapshot. Recorded in the manifest so callers can
 * size their storage budget and so a future
 * "snapshot GC" job can prioritize cheap-to-keep
 * (hardlink) snapshots.
 */
enum class CopyStrategy {
    /**
     * The engine used `cp -al` (POSIX hardlink-based
     * recursive copy). The snapshot's files share
     * inodes with the source at capture time.
     * Writes to the source after the snapshot do
     * NOT affect the snapshot (POSIX guarantees
     * hardlinks are independent after creation);
     * writes to the snapshot's file after creation
     * would break the hardlink. The engine
     * guarantees the snapshot is read-only at the
     * filesystem level.
     */
    HARDLINK,

    /**
     * The engine used a full recursive copy. The
     * snapshot is independent of the source. This
     * is the fallback for cross-filesystem
     * snapshots (e.g. an external SD card source).
     */
    FULL_COPY
}

/**
 * The mount plan active at snapshot time.
 *
 * Phase 49 records the plan but does not act on
 * it (a "rollback to mount plan" operation is a
 * trivial follow-up). The data class lives
 * alongside [WorkspaceSnapshot] because the two
 * are always serialized together in the manifest.
 */
data class MountPlan(
    val mounts: List<MountEntry>,
    val env: Map<String, String> = emptyMap()
) {
    init {
        // MountEntry validates its own invariants;
        // a duplicate guest path is the only
        // workspace-level problem we check here.
        val guestPaths = mounts.map { it.guestPath }
        require(guestPaths.size == guestPaths.toSet().size) {
            "mountPlan has duplicate guest paths: $guestPaths"
        }
    }

    companion object {
        /** An empty plan — used when a workspace
         *  has no mounts configured. */
        val EMPTY: MountPlan = MountPlan(mounts = emptyList(), env = emptyMap())
    }
}
