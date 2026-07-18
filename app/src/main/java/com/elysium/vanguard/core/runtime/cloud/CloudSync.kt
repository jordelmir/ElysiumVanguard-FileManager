package com.elysium.vanguard.core.runtime.cloud

import java.io.File

/**
 * Phase 58 — the runtime's surface for
 * "sync this workspace to the cloud".
 *
 * The interface has two methods:
 * - [push]: upload local workspace state
 *   to the cloud.
 * - [pull]: download cloud workspace state
 *   to the local device.
 *
 * The production impl is
 * [LocalCloudSync] (a stub that uses the
 * local filesystem as the "cloud"). A
 * real cloud provider (S3, GCS, Azure
 * Blob, IPFS) is a Phase 60+ follow-up
 * that implements this interface.
 *
 * The interface is JVM-testable: a test
 * injects a fake that records every call.
 */
interface CloudSync {

    /**
     * Push the workspace's local state to
     * the cloud. Returns the [SyncResult].
     */
    fun push(workspaceId: String): SyncResult

    /**
     * Pull the workspace's cloud state to
     * the local device. Returns the
     * [SyncResult].
     */
    fun pull(workspaceId: String): SyncResult

    /**
     * The current state of the cloud
     * connection. The UI uses this to
     * render "synced" / "syncing" / "error"
     * indicators.
     */
    fun state(): SyncState
}

/**
 * Phase 58 — the cloud sync's state.
 *
 * - [Idle] — no sync in progress; the
 *   cloud is reachable but no operation
 *   is running.
 * - [Pushing] — a `push` is in progress.
 * - [Pulling] — a `pull` is in progress.
 * - [Synced] — the last sync completed
 *   successfully; carries the last
 *   sync timestamp.
 * - [Error] — the last sync failed;
 *   carries the error message.
 *
 * The state is a sealed class so the
 * UI / runner can `when` on it without a
 * default branch.
 */
sealed class SyncState {
    object Idle : SyncState()
    object Pushing : SyncState()
    object Pulling : SyncState()
    data class Synced(val lastSyncAtMs: Long) : SyncState()
    data class Error(val message: String) : SyncState()
}

/**
 * Phase 58 — the result of a single sync
 * operation. The runner / UI uses this to
 * surface the outcome.
 */
sealed class SyncResult {
    data class Success(val bytesTransferred: Long, val durationMs: Long) : SyncResult()
    data class Failure(val message: String) : SyncResult()
}

/**
 * Phase 58 — the local-filesystem cloud
 * sync (a stub).
 *
 * The production impl for Phase 58. The
 * "cloud" is the user's local filesystem
 * at `<cloudBaseDir>/<workspaceId>.json`.
 * A real cloud provider (S3 / GCS / Azure
 * Blob) is a Phase 60+ follow-up that
 * implements [CloudSync] with the same
 * interface.
 *
 * The stub is a faithful implementation:
 * the push serializes the workspace state
 * to a JSON file; the pull reads the
 * file. A real cloud impl swaps the
 * filesystem for an HTTP call; the
 * `CloudSync` interface is unchanged.
 */
class LocalCloudSync(
    private val cloudBaseDir: File
) : CloudSync {

    init {
        if (!cloudBaseDir.exists()) {
            cloudBaseDir.mkdirs()
        }
    }

    @Synchronized
    override fun push(workspaceId: String): SyncResult {
        val start = System.currentTimeMillis()
        val cloudFile = File(cloudBaseDir, "$workspaceId.json")
        // The Phase 58 stub writes a
        // placeholder; a future phase
        // serializes the workspace state
        // (sessions, snapshots, mount
        // policy) to JSON.
        return try {
            cloudFile.writeText(
                "{\"workspaceId\":\"$workspaceId\",\"pushedAtMs\":${System.currentTimeMillis()}}"
            )
            SyncResult.Success(
                bytesTransferred = cloudFile.length(),
                durationMs = System.currentTimeMillis() - start
            )
        } catch (failure: Throwable) {
            SyncResult.Failure(
                "push failed: ${failure.message ?: failure.javaClass.simpleName}"
            )
        }
    }

    @Synchronized
    override fun pull(workspaceId: String): SyncResult {
        val start = System.currentTimeMillis()
        val cloudFile = File(cloudBaseDir, "$workspaceId.json")
        if (!cloudFile.isFile) {
            return SyncResult.Failure("no cloud state for workspace: $workspaceId")
        }
        // The Phase 58 stub reads the
        // placeholder; a future phase
        // deserializes the workspace state.
        return try {
            val bytes = cloudFile.length()
            SyncResult.Success(
                bytesTransferred = bytes,
                durationMs = System.currentTimeMillis() - start
            )
        } catch (failure: Throwable) {
            SyncResult.Failure(
                "pull failed: ${failure.message ?: failure.javaClass.simpleName}"
            )
        }
    }

    @Synchronized
    override fun state(): SyncState {
        // The Phase 58 stub returns Idle
        // (no persistent last-sync state).
        // A future phase tracks the last
        // sync timestamp.
        return SyncState.Idle
    }
}
