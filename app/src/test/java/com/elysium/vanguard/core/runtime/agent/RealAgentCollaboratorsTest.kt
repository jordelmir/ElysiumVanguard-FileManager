package com.elysium.vanguard.core.runtime.agent

import com.elysium.vanguard.core.runtime.build.BuildRequest
import com.elysium.vanguard.core.runtime.build.BuildResult
import com.elysium.vanguard.core.runtime.build.LocalBuildError
import com.elysium.vanguard.core.runtime.build.LocalBuildRunner
import com.elysium.vanguard.core.runtime.build.ToolchainKind
import com.elysium.vanguard.core.runtime.build.ToolchainRegistry
import com.elysium.vanguard.core.runtime.distros.DistroManager
import com.elysium.vanguard.core.runtime.runner.LaunchedProcess
import com.elysium.vanguard.core.runtime.runner.ProcessLauncher
import com.elysium.vanguard.core.runtime.snapshots.MountPlan
import com.elysium.vanguard.core.runtime.snapshots.WorkspaceSnapshot
import com.elysium.vanguard.core.runtime.workspaces.Workspace
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceManager
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.io.IOException

/**
 * Phase 73 — the JVM-side tests for
 * [RealAgentCollaborators].
 *
 * The collaborators are production concrete
 * classes (`DistroManager`,
 * `WorkspaceManager`, `LocalBuildRunner`,
 * `ToolchainRegistry`, `ProcessLauncher`);
 * mockito's inline mock maker is the seam
 * that makes them testable here. Each test
 * stands up mocks for the collaborators +
 * asserts that [RealAgentCollaborators] dispatches
 * the right method with the right arguments
 * and converts the result to the right
 * [AgentStepResult] variant.
 *
 * Coverage:
 *  - `installDistro` — success / failure
 *  - `createWindowsEnvironment` — typed
 *    "not yet wired" failure (Phase 74)
 *  - `createSnapshot` — success on
 *    workspace with LinuxProot session /
 *    failure on workspace with no
 *    LinuxProot session / failure on
 *    missing workspace
 *  - `rollbackToSnapshot` — same three
 *    paths
 *  - `runBuild` — known toolchain success /
 *    unknown toolchain failure / runner
 *    failure
 *  - `runCommand` — success / spawn
 *    failure
 */
class RealAgentCollaboratorsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newCollaborators(
        distroManager: DistroManager = mock(),
        workspaceManager: WorkspaceManager = mock(),
        localBuildRunner: LocalBuildRunner = mock(),
        toolchainRegistry: ToolchainRegistry = mock(),
        processLauncher: ProcessLauncher = mock(),
    ): RealAgentCollaborators = RealAgentCollaborators(
        distroManager = distroManager,
        workspaceManager = workspaceManager,
        localBuildRunner = localBuildRunner,
        toolchainRegistry = toolchainRegistry,
        processLauncher = processLauncher,
    )

    // ============================================================
    // installDistro
    // ============================================================

    @Test
    fun `installDistro returns Success on a successful install`() {
        val rootfs = File(tempFolder.newFolder("elysium-linux-1"), "rootfs").also { it.mkdirs() }
        val distroManager = mock<DistroManager>()
        whenever(distroManager.installBlocking("elysium-linux-1")).thenReturn(Result.success(rootfs))
        val collaborators = newCollaborators(distroManager = distroManager)

        val result = collaborators.installDistro("elysium-linux-1")
        assertTrue("expected Success, got $result", result is AgentStepResult.Success)
        result as AgentStepResult.Success
        assertTrue(
            "expected the message to mention the rootfs path, got: ${result.message}",
            result.message.contains("elysium-linux-1") && result.message.contains("rootfs"),
        )
    }

    @Test
    fun `installDistro returns Failure when the manager fails`() {
        val distroManager = mock<DistroManager>()
        whenever(distroManager.installBlocking("missing-distro"))
            .thenReturn(Result.failure(IOException("Unknown distro: missing-distro")))
        val collaborators = newCollaborators(distroManager = distroManager)

        val result = collaborators.installDistro("missing-distro")
        assertTrue("expected Failure, got $result", result is AgentStepResult.Failure)
        result as AgentStepResult.Failure
        assertTrue(
            "expected the message to mention the failure, got: ${result.message}",
            result.message.contains("Unknown distro"),
        )
    }

    // ============================================================
    // createWindowsEnvironment
    // ============================================================

    @Test
    fun `createWindowsEnvironment returns a typed Phase-74 failure`() {
        val collaborators = newCollaborators()
        val result = collaborators.createWindowsEnvironment(
            binaryPath = "/sdcard/Downloads/setup.exe",
            runtimeKind = "WINE_BOX64",
        )
        assertTrue("expected Failure, got $result", result is AgentStepResult.Failure)
        result as AgentStepResult.Failure
        assertTrue(
            "expected the message to mention Phase 74, got: ${result.message}",
            result.message.contains("Phase 74"),
        )
    }

    // ============================================================
    // createSnapshot
    // ============================================================

    @Test
    fun `createSnapshot returns Success when the workspace has a LinuxProot session`() {
        val workspaceId = "ws-1"
        val rootfs = File(tempFolder.newFolder("elysium-linux-1"), "rootfs").also { it.mkdirs() }
        val workspace = Workspace(
            id = workspaceId,
            name = "E2E Test",
            createdAtMs = 1_700_000_000_000L,
            sessions = listOf(
                WorkspaceSession.LinuxProot(
                    id = "sess-1",
                    displayName = "Elysium",
                    distroId = "elysium-linux-1",
                    profileId = "elysium-linux-1",
                )
            ),
        )
        val snap = WorkspaceSnapshot(
            id = "snap-1",
            workspaceId = workspaceId,
            label = "phase73-snap",
            createdAtMs = 1_700_000_001_000L,
            rootfsPath = "${rootfs.absolutePath}/snapshots/snap-1",
            mountPlan = MountPlan.EMPTY,
            sizeBytes = 0L,
            copyStrategy = com.elysium.vanguard.core.runtime.snapshots.CopyStrategy.HARDLINK,
        )
        // Build the inner mock separately so the
        // outer `whenever` is not nested inside a
        // mock-with-stubbing (which Mockito reports
        // as an "unfinished stubbing" error).
        val installation = mock<com.elysium.vanguard.core.runtime.distros.DistroInstallation>()
        whenever(installation.rootfsDir).thenReturn(rootfs)
        val workspaceManager = mock<WorkspaceManager>()
        val distroManager = mock<DistroManager>()
        whenever(workspaceManager.listWorkspaces()).thenReturn(listOf(workspace))
        whenever(distroManager.findInstalled("elysium-linux-1")).thenReturn(installation)
        whenever(
            workspaceManager.snapshotWorkspace(
                workspaceId = eq(workspaceId),
                sourceRootfsPath = eq(rootfs.absolutePath),
                mountPlan = any<MountPlan>(),
                label = eq("phase73-snap"),
            )
        ).thenReturn(Result.success(snap))
        val collaborators = newCollaborators(
            distroManager = distroManager,
            workspaceManager = workspaceManager,
        )

        val result = collaborators.createSnapshot(workspaceId, "phase73-snap")
        assertTrue("expected Success, got $result", result is AgentStepResult.Success)
        result as AgentStepResult.Success
        assertTrue(
            "expected the message to mention the snapshot id, got: ${result.message}",
            result.message.contains("snap-1"),
        )
        verify(workspaceManager).snapshotWorkspace(
            workspaceId = eq(workspaceId),
            sourceRootfsPath = eq(rootfs.absolutePath),
            mountPlan = any<MountPlan>(),
            label = eq("phase73-snap"),
        )
    }

    @Test
    fun `createSnapshot returns Failure when the workspace is missing`() {
        val workspaceManager = mock<WorkspaceManager>()
        whenever(workspaceManager.listWorkspaces()).thenReturn(emptyList())
        val collaborators = newCollaborators(workspaceManager = workspaceManager)

        val result = collaborators.createSnapshot("ws-missing", "label")
        assertTrue("expected Failure, got $result", result is AgentStepResult.Failure)
        result as AgentStepResult.Failure
        assertTrue(
            "expected the message to mention 'workspace not found', got: ${result.message}",
            result.message.contains("workspace not found"),
        )
    }

    @Test
    fun `createSnapshot returns Failure when the workspace has no LinuxProot session`() {
        val workspace = Workspace(
            id = "ws-1",
            name = "NoSessions",
            createdAtMs = 1_700_000_000_000L,
            sessions = emptyList(),
        )
        val workspaceManager = mock<WorkspaceManager>()
        whenever(workspaceManager.listWorkspaces()).thenReturn(listOf(workspace))
        val collaborators = newCollaborators(workspaceManager = workspaceManager)

        val result = collaborators.createSnapshot("ws-1", "label")
        assertTrue("expected Failure, got $result", result is AgentStepResult.Failure)
        result as AgentStepResult.Failure
        assertTrue(
            "expected the message to mention 'no LinuxProot session', got: ${result.message}",
            result.message.contains("no LinuxProot session"),
        )
    }

    @Test
    fun `createSnapshot returns Failure when the manager's snapshot fails`() {
        val workspaceId = "ws-1"
        val rootfs = File(tempFolder.newFolder("elysium-linux-1"), "rootfs").also { it.mkdirs() }
        val workspace = Workspace(
            id = workspaceId,
            name = "Test",
            createdAtMs = 1_700_000_000_000L,
            sessions = listOf(
                WorkspaceSession.LinuxProot(
                    id = "sess-1",
                    displayName = "Elysium",
                    distroId = "elysium-linux-1",
                    profileId = "elysium-linux-1",
                )
            ),
        )
        val installation = mock<com.elysium.vanguard.core.runtime.distros.DistroInstallation>()
        whenever(installation.rootfsDir).thenReturn(rootfs)
        val workspaceManager = mock<WorkspaceManager>()
        val distroManager = mock<DistroManager>()
        whenever(workspaceManager.listWorkspaces()).thenReturn(listOf(workspace))
        whenever(distroManager.findInstalled("elysium-linux-1")).thenReturn(installation)
        whenever(
            workspaceManager.snapshotWorkspace(
                workspaceId = any<String>(),
                sourceRootfsPath = any<String>(),
                mountPlan = any<MountPlan>(),
                label = any<String>(),
            )
        ).thenReturn(Result.failure(IOException("disk full")))
        val collaborators = newCollaborators(
            distroManager = distroManager,
            workspaceManager = workspaceManager,
        )

        val result = collaborators.createSnapshot(workspaceId, "label")
        assertTrue("expected Failure, got $result", result is AgentStepResult.Failure)
        result as AgentStepResult.Failure
        assertTrue(
            "expected the message to mention 'disk full', got: ${result.message}",
            result.message.contains("disk full"),
        )
    }

    // ============================================================
    // rollbackToSnapshot
    // ============================================================

    @Test
    fun `rollbackToSnapshot returns Success when the workspace and snapshot exist`() {
        val workspaceId = "ws-1"
        val rootfs = File(tempFolder.newFolder("elysium-linux-1"), "rootfs").also { it.mkdirs() }
        val workspace = Workspace(
            id = workspaceId,
            name = "Test",
            createdAtMs = 1_700_000_000_000L,
            sessions = listOf(
                WorkspaceSession.LinuxProot(
                    id = "sess-1",
                    displayName = "Elysium",
                    distroId = "elysium-linux-1",
                    profileId = "elysium-linux-1",
                )
            ),
        )
        val snap = WorkspaceSnapshot(
            id = "snap-1",
            workspaceId = workspaceId,
            label = "phase73",
            createdAtMs = 1_700_000_001_000L,
            rootfsPath = "${rootfs.absolutePath}/snapshots/snap-1",
            mountPlan = MountPlan.EMPTY,
            sizeBytes = 0L,
            copyStrategy = com.elysium.vanguard.core.runtime.snapshots.CopyStrategy.HARDLINK,
        )
        val installation = mock<com.elysium.vanguard.core.runtime.distros.DistroInstallation>()
        whenever(installation.rootfsDir).thenReturn(rootfs)
        val workspaceManager = mock<WorkspaceManager>()
        val distroManager = mock<DistroManager>()
        whenever(workspaceManager.listWorkspaces()).thenReturn(listOf(workspace))
        whenever(distroManager.findInstalled("elysium-linux-1")).thenReturn(installation)
        whenever(
            workspaceManager.rollbackWorkspace(
                workspaceId = eq(workspaceId),
                snapshotId = eq("snap-1"),
                liveRootfsPath = eq(rootfs.absolutePath),
            )
        ).thenReturn(Result.success(snap))
        val collaborators = newCollaborators(
            distroManager = distroManager,
            workspaceManager = workspaceManager,
        )

        val result = collaborators.rollbackToSnapshot(workspaceId, "snap-1")
        assertTrue("expected Success, got $result", result is AgentStepResult.Success)
        result as AgentStepResult.Success
        assertTrue(
            "expected the message to mention the workspace + snapshot, got: ${result.message}",
            result.message.contains(workspaceId) && result.message.contains("snap-1"),
        )
    }

    @Test
    fun `rollbackToSnapshot returns Failure when the workspace is missing`() {
        val workspaceManager = mock<WorkspaceManager>()
        whenever(workspaceManager.listWorkspaces()).thenReturn(emptyList())
        val collaborators = newCollaborators(workspaceManager = workspaceManager)

        val result = collaborators.rollbackToSnapshot("ws-missing", "snap-1")
        assertTrue("expected Failure, got $result", result is AgentStepResult.Failure)
    }

    // ============================================================
    // runBuild
    // ============================================================

    @Test
    fun `runBuild returns Success on a known toolchain with a successful build`() {
        val buildRunner = mock<LocalBuildRunner>()
        val registry = mock<ToolchainRegistry>()
        val install = com.elysium.vanguard.core.runtime.build.ToolchainInstall(
            binaryPath = File("/usr/bin/cargo"),
            version = "1.74.0",
        )
        whenever(registry.installFor(ToolchainKind.RUST)).thenReturn(install)
        whenever(
            buildRunner.build(any<BuildRequest>(), any<ToolchainRegistry>())
        ).thenReturn(
            Result.success(
                BuildResult.Success(
                    exitCode = 0,
                    stdout = "Compiled 42 crates",
                    stderr = "",
                    durationMs = 1234L,
                )
            )
        )
        val collaborators = newCollaborators(
            localBuildRunner = buildRunner,
            toolchainRegistry = registry,
        )

        val result = collaborators.runBuild("rust", listOf("build", "--release"))
        assertTrue("expected Success, got $result", result is AgentStepResult.Success)
        result as AgentStepResult.Success
        // The message echoes the input `toolchainKind` (lowercased),
        // not the parsed `ToolchainKind` enum, so the assertion is
        // case-insensitive on the input.
        assertTrue(
            "expected the message to mention the toolchain + exit, got: ${result.message}",
            result.message.contains("rust", ignoreCase = true) && result.message.contains("exit"),
        )
    }

    @Test
    fun `runBuild returns Failure on an unknown toolchain kind`() {
        val collaborators = newCollaborators()
        val result = collaborators.runBuild("MYSTERY", listOf("build"))
        assertTrue("expected Failure, got $result", result is AgentStepResult.Failure)
        result as AgentStepResult.Failure
        assertTrue(
            "expected the message to mention 'unknown toolchain', got: ${result.message}",
            result.message.contains("unknown toolchain"),
        )
    }

    @Test
    fun `runBuild returns Failure when the runner reports a typed error`() {
        val buildRunner = mock<LocalBuildRunner>()
        val registry = mock<ToolchainRegistry>()
        val install = com.elysium.vanguard.core.runtime.build.ToolchainInstall(
            binaryPath = File("/usr/bin/gradle"),
            version = "8.5",
        )
        whenever(registry.installFor(ToolchainKind.GRADLE)).thenReturn(install)
        whenever(
            buildRunner.build(any<BuildRequest>(), any<ToolchainRegistry>())
        ).thenReturn(Result.failure(LocalBuildError.SpawnFailed("binary not found")))
        val collaborators = newCollaborators(
            localBuildRunner = buildRunner,
            toolchainRegistry = registry,
        )

        val result = collaborators.runBuild("gradle", listOf("assemble"))
        assertTrue("expected Failure, got $result", result is AgentStepResult.Failure)
        result as AgentStepResult.Failure
        assertTrue(
            "expected the message to mention 'binary not found', got: ${result.message}",
            result.message.contains("binary not found"),
        )
    }

    // ============================================================
    // runCommand
    // ============================================================

    @Test
    fun `runCommand returns Success with the spawned pid`() {
        val launcher = mock<ProcessLauncher>()
        whenever(
            launcher.start(
                command = eq(listOf("/bin/ls", "-la", "/sdcard")),
                env = any<List<Pair<String, String>>>(),
                cwd = any<File>(),
            )
        ).thenReturn(LaunchedProcess(pid = 4242) { /* no-op stop */ })
        val collaborators = newCollaborators(processLauncher = launcher)

        val result = collaborators.runCommand(
            command = listOf("/bin/ls", "-la", "/sdcard"),
            workingDirectory = null,
        )
        assertTrue("expected Success, got $result", result is AgentStepResult.Success)
        result as AgentStepResult.Success
        assertTrue(
            "expected the message to mention the pid, got: ${result.message}",
            result.message.contains("4242"),
        )
    }

    @Test
    fun `runCommand returns Failure on a spawn IOException`() {
        val launcher = mock<ProcessLauncher>()
        // Mockito's `thenThrow(IOException(...))` rejects checked
        // exceptions that the mocked method doesn't declare; the
        // `ProcessLauncher.start` signature only allows unchecked
        // throws, so we throw an `IOException` wrapped inside a
        // `RuntimeException` to model the same failure (the
        // `RealAgentCollaborators.runCommand` catches
        // `IOException` and `RuntimeException` wraps it).
        whenever(
            launcher.start(
                command = any<List<String>>(),
                env = any<List<Pair<String, String>>>(),
                cwd = any<File>(),
            )
        ).thenThrow(RuntimeException("binary not found"))
        val collaborators = newCollaborators(processLauncher = launcher)

        val result = collaborators.runCommand(
            command = listOf("/bin/missing"),
            workingDirectory = null,
        )
        assertTrue("expected Failure, got $result", result is AgentStepResult.Failure)
        result as AgentStepResult.Failure
        assertTrue(
            "expected the message to mention 'binary not found', got: ${result.message}",
            result.message.contains("binary not found"),
        )
    }

    @Test
    fun `runCommand returns Failure when the command list is empty`() {
        val collaborators = newCollaborators()
        val result = collaborators.runCommand(command = emptyList(), workingDirectory = null)
        assertTrue("expected Failure, got $result", result is AgentStepResult.Failure)
        result as AgentStepResult.Failure
        assertTrue(
            "expected the message to mention 'empty', got: ${result.message}",
            result.message.contains("empty"),
        )
    }

    // ============================================================
    // Toolchain parsing (case-insensitive, supports the rule-based
    // parser's English + Spanish + abbreviated forms).
    // ============================================================

    @Test
    fun `runBuild normalizes the toolchain kind to upper case`() {
        val buildRunner = mock<LocalBuildRunner>()
        val registry = mock<ToolchainRegistry>()
        val install = com.elysium.vanguard.core.runtime.build.ToolchainInstall(
            binaryPath = File("/usr/bin/node"),
            version = "20.0.0",
        )
        whenever(registry.installFor(ToolchainKind.NODE)).thenReturn(install)
        whenever(
            buildRunner.build(any<BuildRequest>(), any<ToolchainRegistry>())
        ).thenReturn(
            Result.success(
                BuildResult.Success(
                    exitCode = 0,
                    stdout = "ok",
                    stderr = "",
                    durationMs = 1L,
                )
            )
        )
        val collaborators = newCollaborators(
            localBuildRunner = buildRunner,
            toolchainRegistry = registry,
        )

        val result = collaborators.runBuild("node", listOf("--version"))
        assertTrue("expected Success, got $result", result is AgentStepResult.Success)
    }
}
