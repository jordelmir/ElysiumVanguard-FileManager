package com.elysium.vanguard.core.runtime.snapshots

/**
 * Phase 49 — the runtime-side contract for
 * capturing, restoring, listing, and deleting
 * workspace snapshots.
 *
 * The engine is a pure storage-IO layer. It does
 * not know about [com.elysium.vanguard.core.runtime.workspaces.WorkspaceManager],
 * the
 * [com.elysium.vanguard.core.runtime.observability.RuntimeEventBus],
 * or any other runtime component. The manager is
 * the orchestrator; the engine is the
 * persistence seam.
 *
 * Implementations:
 *
 * - [FilesystemSnapshotEngine] — the production
 *   impl. Uses `cp -al` (hardlink) on
 *   same-filesystem snapshots and falls back to a
 *   full recursive copy on cross-filesystem
 *   snapshots. On-disk layout:
 *   `<filesDir>/workspaces/<workspaceId>/snapshots/<snapshotId>/{manifest.json, rootfs/}`.
 *
 * - `InMemorySnapshotEngine` — the test impl.
 *   Lives at
 *   `app/src/test/java/com/elysium/vanguard/core/runtime/snapshots/`
 *   and is used by [WorkspaceManager] tests.
 *
 * A future cloud-backed impl would implement this
 * interface with a remote object store (S3,
 * GCS, ...). The interface is the swap-out point.
 *
 * Thread safety: implementations must be safe to
 * call from multiple coroutines. The
 * [FilesystemSnapshotEngine] uses a per-workspace
 * lock to serialise snapshot / rollback / delete
 * calls against the same workspace; concurrent
 * operations against *different* workspaces are
 * fully concurrent.
 */
interface SnapshotEngine {

    /**
     * Capture a snapshot of [sourceRootfsPath].
     *
     * The engine records [mountPlan] in the
     * manifest (Phase 49 records but does not act
     * on it). The engine picks the cheapest copy
     * strategy that succeeds; the chosen strategy
     * is recorded in the returned snapshot.
     *
     * @param workspaceId the owning workspace.
     * @param sourceRootfsPath the absolute path to
     *   the live rootfs to capture.
     * @param mountPlan the bind mounts + env that
     *   are in effect at snapshot time.
     * @param label a user-facing label. Must be
     *   non-blank.
     * @param nowMs the timestamp to record in the
     *   snapshot's `createdAtMs` (defaults to the
     *   engine's clock; tests pass an explicit
     *   value).
     *
     * @return a [SnapshotResult] — success carries
     *   the new [WorkspaceSnapshot]; failure
     *   carries a typed [SnapshotError].
     */
    fun snapshot(
        workspaceId: String,
        sourceRootfsPath: String,
        mountPlan: MountPlan,
        label: String,
        nowMs: Long? = null
    ): SnapshotResult

    /**
     * Restore a workspace to a previously captured
     * snapshot.
     *
     * The engine replaces the live rootfs at
     * [liveRootfsPath] with the snapshot's rootfs.
     * The previous live rootfs is NOT preserved —
     * a rollback is destructive. The caller (the
     * manager) is responsible for taking a
     * "pre-rollback" snapshot if the user wants
     * undo.
     *
     * Phase 49 does not act on the snapshot's
     * [MountPlan]; a future "rollback to mount
     * plan" operation is a trivial follow-up.
     *
     * @param workspaceId the owning workspace.
     * @param snapshotId the snapshot to restore.
     * @param liveRootfsPath the absolute path of
     *   the live rootfs to overwrite.
     *
     * @return a [RollbackResult] — success carries
     *   the [WorkspaceSnapshot] that was restored;
     *   failure carries a typed [SnapshotError].
     */
    fun rollback(
        workspaceId: String,
        snapshotId: String,
        liveRootfsPath: String
    ): RollbackResult

    /**
     * List every snapshot of [workspaceId], sorted
     * by `createdAtMs` ascending (oldest first).
     * Returns an empty list if the workspace has no
     * snapshots.
     */
    fun list(workspaceId: String): List<WorkspaceSnapshot>

    /**
     * Delete a snapshot. Returns `true` on success,
     * `false` if the snapshot does not exist.
     */
    fun delete(snapshotId: String): Boolean
}

/** Result of a [SnapshotEngine.snapshot] call. */
sealed class SnapshotResult {
    data class Success(val snapshot: WorkspaceSnapshot) : SnapshotResult()
    data class Failure(val error: SnapshotError) : SnapshotResult()
}

/** Result of a [SnapshotEngine.rollback] call. */
sealed class RollbackResult {
    data class Success(val restoredFrom: WorkspaceSnapshot) : RollbackResult()
    data class Failure(val error: SnapshotError) : RollbackResult()
}

/**
 * Typed errors the engine returns. The caller
 * (the manager) branches on the kind rather than
 * parsing free-form strings.
 */
sealed class SnapshotError(message: String) : RuntimeException(message) {

    /** The requested snapshot does not exist. */
    data class SnapshotNotFound(val snapshotId: String) :
        SnapshotError("Snapshot not found: $snapshotId")

    /** The source rootfs to capture does not exist. */
    data class SourceNotFound(val path: String) :
        SnapshotError("Source rootfs not found: $path")

    /** The live rootfs to overwrite does not exist. */
    data class LiveRootfsNotFound(val path: String) :
        SnapshotError("Live rootfs not found: $path")

    /** The label is blank or otherwise invalid. */
    data class InvalidLabel(val label: String) :
        SnapshotError("Invalid snapshot label: '$label'")

    /** The copy operation failed (cp returned non-zero). */
    data class CopyFailed(val command: String, val exitCode: Int, val stderr: String) :
        SnapshotError("Copy command '$command' exited $exitCode: $stderr")

    /** The I/O subsystem reported an error
     *  (process spawn failed, file write failed,
     *  etc.). */
    data class IoError(val details: String) :
        SnapshotError("I/O error: $details")
}
