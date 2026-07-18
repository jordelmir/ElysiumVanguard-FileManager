package com.elysium.vanguard.core.runtime.cloud

import com.elysium.vanguard.core.runtime.build.BuildRequest
import com.elysium.vanguard.core.runtime.build.RemoteBuildClient
import com.elysium.vanguard.core.runtime.build.RemoteBuildResult
import java.io.File

/**
 * Phase 58 — the production HTTP impl of
 * the [RemoteBuildClient] interface.
 *
 * The client sends a [BuildRequest] to a
 * remote build server (Oracle Free tier or
 * a user's own server) and receives a
 * [RemoteBuildResult] (the artifact +
 * SBOM + manifest). The client uses
 * `HttpURLConnection` (JDK-native; no
 * third-party HTTP client) and HTTPS with
 * a bearer token (the user's auth token).
 *
 * Phase 58 ships the client as the seam;
 * the actual HTTP impl uses the JDK's
 * `HttpURLConnection` directly. The server
 * is a separate project (the "Oracle Free
 * build server") that ships in a Phase
 * 60+ follow-up. The client is JVM-
 * testable: a test injects a
 * [FakeHttpRemoteBuildClient] that
 * records every call.
 */
interface HttpRemoteBuildClient : RemoteBuildClient {
    /**
     * The base URL of the remote build
     * server. e.g.
     * `https://build.elysium-vanguard.example.com`.
     */
    val baseUrl: String

    /**
     * The bearer token for authentication.
     * The runtime reads the token from the
     * user's account settings (Phase 60+).
     */
    val authToken: String
}

/**
 * Phase 58 — the [HttpRemoteBuildClient]
 * stub.
 *
 * Phase 58 ships a stub class that the
 * user can replace for a real server. The
 * default `HttpRemoteBuildClientImpl` is a
 * Phase 60+ follow-up that uses
 * `HttpURLConnection` directly.
 *
 * The stub returns a [RemoteBuildResult]
 * with a placeholder artifact. A real impl
 * POSTs the request to the server, reads
 * the response, and returns the actual
 * artifact.
 */
class HttpRemoteBuildClientStub(
    override val baseUrl: String = "https://build.elysium-vanguard.example.com",
    override val authToken: String = "stub-token"
) : HttpRemoteBuildClient {

    override fun build(request: BuildRequest): Result<RemoteBuildResult> {
        // The Phase 58 stub returns a
        // placeholder artifact. A future
        // phase uses HttpURLConnection to
        // POST the request.
        val artifactName = "build-${request.kind.name.lowercase()}-output.bin"
        val artifactBytes = ByteArray(64) { 0x42 }
        return Result.success(
            RemoteBuildResult(
                exitCode = 0,
                artifactBytes = artifactBytes,
                artifactName = artifactName,
                sbom = "{\"name\":\"$artifactName\",\"phase\":58}"
            )
        )
    }
}

/**
 * Phase 58 — the [BackupService].
 *
 * The service composes a workspace snapshot
 * (Phase 49) with the cloud sync: the
 * snapshot is the local backup; the cloud
 * sync is the remote backup.
 *
 * The service exposes:
 * - [backup(workspaceId)]: capture a
 *   snapshot of the workspace's live
 *   rootfs, encrypt it with the workspace's
 *   vault key (Tink AES-256-GCM), push
 *   the encrypted bytes to the cloud.
 * - [restore(workspaceId, snapshotId)]:
 *   pull the encrypted backup from the
 *   cloud, decrypt it, restore the live
 *   rootfs to the snapshot state.
 *
 * The backup is encrypted with the
 * existing Tink (Phase 9.4.2) AES-256-GCM
 * stack. The encryption key is the
 * workspace's vault key (a per-workspace
 * key generated at workspace creation).
 */
class BackupService(
    private val cloudSync: CloudSync,
    private val backupBaseDir: File
) {
    init {
        if (!backupBaseDir.exists()) {
            backupBaseDir.mkdirs()
        }
    }

    /**
     * Backup [workspaceId] to the cloud.
     * Phase 58 ships a stub that writes a
     * placeholder to the local backup
     * dir; a future phase encrypts with
     * Tink + pushes to the cloud.
     */
    fun backup(workspaceId: String): BackupResult {
        val backupFile = File(backupBaseDir, "$workspaceId.backup")
        return try {
            backupFile.writeText(
                "{\"workspaceId\":\"$workspaceId\",\"backedUpAtMs\":${System.currentTimeMillis()}}"
            )
            val sync = cloudSync.push(workspaceId)
            when (sync) {
                is SyncResult.Success -> BackupResult.Success(
                    backupFile = backupFile,
                    cloudBytes = sync.bytesTransferred
                )
                is SyncResult.Failure -> BackupResult.Failure(
                    "cloud push failed: ${sync.message}"
                )
            }
        } catch (failure: Throwable) {
            BackupResult.Failure(
                "backup failed: ${failure.message ?: failure.javaClass.simpleName}"
            )
        }
    }

    /**
     * Restore [workspaceId] from a cloud
     * backup. Phase 58 ships a stub that
     * pulls from the cloud; a future phase
     * decrypts + applies the snapshot to
     * the live rootfs.
     */
    fun restore(workspaceId: String, snapshotId: String): BackupResult {
        val sync = cloudSync.pull(workspaceId)
        return when (sync) {
            is SyncResult.Success -> BackupResult.Success(
                backupFile = File(backupBaseDir, "$workspaceId.backup"),
                cloudBytes = sync.bytesTransferred
            )
            is SyncResult.Failure -> BackupResult.Failure(
                "cloud pull failed: ${sync.message}"
            )
        }
    }
}

/**
 * Phase 58 — the result of a backup or
 * restore operation.
 */
sealed class BackupResult {
    data class Success(
        val backupFile: File,
        val cloudBytes: Long
    ) : BackupResult()
    data class Failure(val message: String) : BackupResult()
}
