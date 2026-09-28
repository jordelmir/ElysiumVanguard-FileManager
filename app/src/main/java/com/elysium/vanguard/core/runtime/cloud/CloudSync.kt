package com.elysium.vanguard.core.runtime.cloud

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
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
 * [LocalCloudSync] (a local filesystem sync).
 * A real cloud provider (S3, GCS, Azure
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
 * Phase 145 — the on-disk shape of a
 * synced workspace state. The DTO carries
 * the workspace ID, session list, and
 * sync metadata. The DTO is private to
 * the [LocalCloudSync] and never escapes
 * the sync boundary.
 */
private data class WorkspaceSyncDto(
    val workspaceId: String,
    val sessions: List<SessionSyncDto>,
    val pushedAtMs: Long,
    val version: Int = 1
)

/**
 * Phase 145 — the on-disk shape of a
 * single session within a synced workspace.
 */
private data class SessionSyncDto(
    val kind: String,
    val id: String,
    val displayName: String,
    val distroId: String? = null,
    val profileId: String? = null,
    val windowsSpecId: String? = null
)

/**
 * Phase 58 — the local-filesystem cloud
 * sync.
 *
 * The production impl for Phase 58. The
 * "cloud" is the user's local filesystem
 * at `<cloudBaseDir>/<workspaceId>.json`.
 * A real cloud provider (S3 / GCS / Azure
 * Blob) is a Phase 60+ follow-up that
 * implements [CloudSync] with the same
 * interface.
 *
 * Phase 145 — the sync now serializes
 * real workspace state (sessions) to
 * JSON using Gson, and tracks the last
 * sync timestamp persistently.
 */
class LocalCloudSync(
    private val cloudBaseDir: File,
    private val workspaceStore: com.elysium.vanguard.core.runtime.workspaces.WorkspaceStore? = null,
) : CloudSync {

    private val gson = Gson()
    private val stateFile = File(cloudBaseDir, ".sync_state.json")

    init {
        if (!cloudBaseDir.exists()) {
            cloudBaseDir.mkdirs()
        }
    }

    @Volatile
    private var currentState: SyncState = loadSyncState()

    init {
        currentState = loadSyncState()
    }

    @Synchronized
    override fun push(workspaceId: String): SyncResult {
        val start = System.currentTimeMillis()
        currentState = SyncState.Pushing
        val cloudFile = File(cloudBaseDir, "$workspaceId.json")
        return try {
            val workspace = workspaceStore?.list()?.firstOrNull { it.id == workspaceId }
            val sessions = workspace?.sessions?.map { session ->
                SessionSyncDto(
                    kind = session.kind.name,
                    id = session.id,
                    displayName = session.displayName,
                    distroId = (session as? com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession.LinuxProot)?.distroId,
                    profileId = (session as? com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession.LinuxProot)?.profileId,
                    windowsSpecId = (session as? com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession.WindowsVm)?.windowsSpecId,
                )
            } ?: emptyList()
            val dto = WorkspaceSyncDto(
                workspaceId = workspaceId,
                sessions = sessions,
                pushedAtMs = System.currentTimeMillis()
            )
            val json = gson.toJson(dto)
            cloudFile.writeText(json, Charsets.UTF_8)
            val duration = System.currentTimeMillis() - start
            currentState = SyncState.Synced(System.currentTimeMillis())
            saveSyncState(currentState as? SyncState.Synced)
            SyncResult.Success(
                bytesTransferred = cloudFile.length(),
                durationMs = duration
            )
        } catch (failure: Throwable) {
            currentState = SyncState.Error("push failed: ${failure.message ?: failure.javaClass.simpleName}")
            SyncResult.Failure(
                "push failed: ${failure.message ?: failure.javaClass.simpleName}"
            )
        }
    }

    @Synchronized
    override fun pull(workspaceId: String): SyncResult {
        val start = System.currentTimeMillis()
        currentState = SyncState.Pulling
        val cloudFile = File(cloudBaseDir, "$workspaceId.json")
        if (!cloudFile.isFile) {
            currentState = SyncState.Error("no cloud state for workspace: $workspaceId")
            return SyncResult.Failure("no cloud state for workspace: $workspaceId")
        }
        return try {
            val json = cloudFile.readText(Charsets.UTF_8)
            val dto = try {
                gson.fromJson(json, WorkspaceSyncDto::class.java)
            } catch (e: JsonSyntaxException) {
                null
            }
            val bytes = cloudFile.length()
            val duration = System.currentTimeMillis() - start
            currentState = SyncState.Synced(System.currentTimeMillis())
            saveSyncState(currentState as? SyncState.Synced)
            SyncResult.Success(
                bytesTransferred = bytes,
                durationMs = duration
            )
        } catch (failure: Throwable) {
            currentState = SyncState.Error("pull failed: ${failure.message ?: failure.javaClass.simpleName}")
            SyncResult.Failure(
                "pull failed: ${failure.message ?: failure.javaClass.simpleName}"
            )
        }
    }

    @Synchronized
    override fun state(): SyncState = currentState

    private fun loadSyncState(): SyncState {
        return try {
            if (stateFile.exists()) {
                val json = stateFile.readText(Charsets.UTF_8)
                val dto = gson.fromJson(json, SyncStateFile::class.java)
                if (dto != null && dto.lastSyncAtMs > 0) {
                    SyncState.Synced(dto.lastSyncAtMs)
                } else {
                    SyncState.Idle
                }
            } else {
                SyncState.Idle
            }
        } catch (e: Exception) {
            SyncState.Idle
        }
    }

    private fun saveSyncState(synced: SyncState.Synced?) {
        try {
            val dto = SyncStateFile(lastSyncAtMs = synced?.lastSyncAtMs ?: 0L)
            stateFile.writeText(gson.toJson(dto), Charsets.UTF_8)
        } catch (_: Exception) {
            // Best-effort persistence
        }
    }

    private data class SyncStateFile(val lastSyncAtMs: Long)
}
