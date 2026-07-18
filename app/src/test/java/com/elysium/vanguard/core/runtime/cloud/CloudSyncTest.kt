package com.elysium.vanguard.core.runtime.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase 58 — tests for [CloudSync] +
 * [LocalCloudSync] + [BackupService] +
 * [HttpRemoteBuildClientStub].
 *
 * The tests pin:
 *
 *   - [LocalCloudSync.push] writes a
 *     placeholder to the cloud base dir.
 *   - [LocalCloudSync.pull] reads the
 *     placeholder.
 *   - [LocalCloudSync.pull] returns a
 *     typed failure for an unknown
 *     workspace.
 *   - [LocalCloudSync.state] returns Idle
 *     in the stub.
 *   - [BackupService.backup] writes a
 *     local backup file AND pushes to the
 *     cloud.
 *   - [BackupService.restore] pulls from
 *     the cloud.
 *   - [HttpRemoteBuildClientStub.build]
 *     returns a placeholder artifact.
 */
class CloudSyncTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cloudBaseDir: File
    private lateinit var backupBaseDir: File
    private lateinit var cloudSync: LocalCloudSync
    private lateinit var backupService: BackupService

    @Before
    fun setUp() {
        cloudBaseDir = tempFolder.newFolder("cloud")
        backupBaseDir = tempFolder.newFolder("backups")
        cloudSync = LocalCloudSync(cloudBaseDir = cloudBaseDir)
        backupService = BackupService(
            cloudSync = cloudSync,
            backupBaseDir = backupBaseDir
        )
    }

    // --- LocalCloudSync ---

    @Test
    fun `push writes a placeholder file to the cloud base dir`() {
        val result = cloudSync.push("ws-1")
        assertTrue("expected Success, got $result", result is SyncResult.Success)
        val success = result as SyncResult.Success
        assertTrue("bytesTransferred should be positive", success.bytesTransferred > 0)
        val cloudFile = File(cloudBaseDir, "ws-1.json")
        assertTrue("cloud file should exist", cloudFile.isFile)
        val content = cloudFile.readText()
        assertTrue(
            "cloud file should contain the workspace id: $content",
            content.contains("ws-1")
        )
    }

    @Test
    fun `pull reads the cloud file`() {
        cloudSync.push("ws-1")
        val result = cloudSync.pull("ws-1")
        assertTrue("expected Success, got $result", result is SyncResult.Success)
    }

    @Test
    fun `pull returns Failure for an unknown workspace`() {
        val result = cloudSync.pull("ws-no-such")
        assertTrue(result is SyncResult.Failure)
        val failure = result as SyncResult.Failure
        assertTrue(
            "failure should mention the missing workspace: ${failure.message}",
            failure.message.contains("ws-no-such")
        )
    }

    @Test
    fun `state returns Idle in the stub`() {
        val state = cloudSync.state()
        assertTrue("expected Idle, got $state", state is SyncState.Idle)
    }

    // --- BackupService ---

    @Test
    fun `backup writes a local file and pushes to the cloud`() {
        val result = backupService.backup("ws-1")
        assertTrue("expected Success, got $result", result is BackupResult.Success)
        val success = result as BackupResult.Success
        assertTrue("backup file should exist", success.backupFile.isFile)
        // The cloud should also have a
        // file (push was called).
        val cloudFile = File(cloudBaseDir, "ws-1.json")
        assertTrue("cloud file should exist", cloudFile.isFile)
    }

    @Test
    fun `restore pulls from the cloud`() {
        // First, push so the cloud has a
        // file to pull.
        cloudSync.push("ws-1")
        val result = backupService.restore("ws-1", "snap-1")
        assertTrue("expected Success, got $result", result is BackupResult.Success)
    }

    @Test
    fun `restore returns Failure when the cloud has no state for the workspace`() {
        val result = backupService.restore("ws-no-such", "snap-1")
        assertTrue(result is BackupResult.Failure)
    }

    // --- HttpRemoteBuildClientStub ---

    @Test
    fun `HttpRemoteBuildClientStub returns a placeholder artifact`() {
        val client = HttpRemoteBuildClientStub()
        assertEquals(
            "https://build.elysium-vanguard.example.com",
            client.baseUrl
        )
        assertEquals("stub-token", client.authToken)
        val request = com.elysium.vanguard.core.runtime.build.BuildRequest(
            projectPath = File("/fake/project"),
            kind = com.elysium.vanguard.core.runtime.build.ToolchainKind.RUST,
            command = listOf("build", "--release")
        )
        val result = client.build(request)
        assertTrue("expected Success, got $result", result.isSuccess)
        val buildResult = result.getOrThrow()
        assertEquals(0, buildResult.exitCode)
        assertNotNull(buildResult.artifactName)
        assertTrue(
            "artifactName should mention rust: ${buildResult.artifactName}",
            buildResult.artifactName.contains("rust", ignoreCase = true)
        )
        assertTrue(
            "sbom should be non-empty: ${buildResult.sbom}",
            buildResult.sbom.isNotEmpty()
        )
    }

    // --- SyncState / SyncResult ---

    @Test
    fun `SyncState is a sealed class with 5 variants`() {
        // Compile-time check: the sealed
        // class has 5 variants (Idle,
        // Pushing, Pulling, Synced, Error).
        val states: List<SyncState> = listOf(
            SyncState.Idle,
            SyncState.Pushing,
            SyncState.Pulling,
            SyncState.Synced(lastSyncAtMs = 1L),
            SyncState.Error(message = "test")
        )
        assertEquals(5, states.size)
    }
}
