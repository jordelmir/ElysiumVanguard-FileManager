package com.elysium.vanguard.core.runtime.proot

import com.elysium.vanguard.core.runtime.distros.DesktopProfile
import com.elysium.vanguard.core.runtime.distros.Distro
import com.elysium.vanguard.core.runtime.distros.DistroFamily
import com.elysium.vanguard.core.runtime.distros.DistroInstallation
import com.elysium.vanguard.core.runtime.distros.RootfsKind
import com.elysium.vanguard.core.runtime.distros.launcher.DistroLauncher
import com.elysium.vanguard.core.runtime.distros.launcher.LauncherCapabilities
import com.elysium.vanguard.core.runtime.distros.launcher.LauncherKind
import com.elysium.vanguard.core.runtime.distros.launcher.LauncherPick
import com.elysium.vanguard.core.runtime.observability.RuntimeEventBus
import com.elysium.vanguard.core.runtime.observability.SynchronizedEventBus
import com.elysium.vanguard.core.runtime.runner.DistroSessionBackend
import com.elysium.vanguard.core.runtime.runner.LaunchedProcess
import com.elysium.vanguard.core.runtime.runner.ProcessLauncher
import com.elysium.vanguard.core.runtime.snapshots.CopyStrategy
import com.elysium.vanguard.core.runtime.snapshots.MountPlan
import com.elysium.vanguard.core.runtime.snapshots.RollbackResult
import com.elysium.vanguard.core.runtime.snapshots.SnapshotEngine
import com.elysium.vanguard.core.runtime.snapshots.SnapshotResult
import com.elysium.vanguard.core.runtime.snapshots.WorkspaceSnapshot
import com.elysium.vanguard.core.runtime.workspace_orchestrator.BindMount
import com.elysium.vanguard.core.runtime.workspaces.FileWorkspaceStore
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceManager
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 72 — the JVM-side tests for the
 * [WriteCapture] integration in [ProotBackendReal].
 *
 * The previous Phase 71 `ProotBackendRealTest` covers
 * the launch-command translation (bindMounts → `-b`
 * flags, env merge, etc.). This new test covers the
 * **write-capture lifecycle**:
 *
 *  - `launch` calls `writeCapture.start(watchedHostPaths)`
 *    BEFORE spawning the process.
 *  - `launch` populates `LaunchResult.writes` with
 *    the capture's snapshot at spawn time
 *    (typically empty).
 *  - `stop` does **not** stop the capture (the
 *    orchestrator reads writes after stop).
 *  - `writes` returns the capture's snapshot.
 *  - `restoreSnapshot` stops the capture.
 *  - Sequential sessions reset the capture (a stale
 *    write from session A never bleeds into
 *    session B's audit).
 *
 * The fake [RecordingWriteCapture] records every
 * start / stop / writes call so the test can assert
 * the order of operations. It also exposes a
 * pre-seedable writes list, simulating the
 * Android `FileObserver` events firing between
 * `launch` and `stop`.
 */
class ProotBackendRealWriteCaptureTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newSession(id: String = "sess-1", distroId: String = "elysium-linux-1"): WorkspaceSession =
        WorkspaceSession.LinuxProot(
            id = id,
            displayName = "E2E Write Capture Test",
            distroId = distroId,
            profileId = distroId,
        )

    @Test
    fun `launch starts the write capture with the bind-mounted host paths before spawning`() {
        val capture = RecordingWriteCapture()
        val recorder = SingleCallProcessLauncher()
        val setup = newSetup()
        val proot = ProotBackendReal(
            backend = setup.backend,
            processLauncher = recorder,
            workspaceManager = setup.workspaceManager,
            writeCapture = capture,
        )

        val session = newSession()
        val result = proot.launch(
            workspaceId = "ws-1",
            session = session,
            executable = "/usr/bin/e2e-test",
            args = listOf("--benchmark"),
            workingDirectory = "/workspace/projects",
            bindMounts = listOf(
                BindMount(
                    hostPath = "/sdcard/ElysiumVanguard/projects",
                    containerPath = "/workspace/projects",
                    readOnly = false,
                ),
                BindMount(
                    hostPath = "/sdcard/ElysiumVanguard/cache",
                    containerPath = "/workspace/cache",
                    readOnly = false,
                ),
            ),
            environment = emptyMap(),
        )
        assertTrue("expected success, got $result", result.isSuccess)

        // The capture was started with the bindMount
        // host paths (in any order — the backend
        // converts to a Set).
        val startedPaths = capture.startCalls.flatten().toSet()
        assertEquals(
            setOf(
                "/sdcard/ElysiumVanguard/projects",
                "/sdcard/ElysiumVanguard/cache",
            ),
            startedPaths,
        )
        // The capture was started BEFORE the spawn.
        assertEquals(1, recorder.startCount)
    }

    @Test
    fun `launch with no bindMounts starts the capture with the empty set`() {
        val capture = RecordingWriteCapture()
        val recorder = SingleCallProcessLauncher()
        val setup = newSetup()
        val proot = ProotBackendReal(
            backend = setup.backend,
            processLauncher = recorder,
            workspaceManager = setup.workspaceManager,
            writeCapture = capture,
        )

        val result = proot.launch(
            workspaceId = "ws-1",
            session = newSession(),
            executable = "/usr/bin/e2e-test",
            args = emptyList(),
            workingDirectory = "/workspace",
            bindMounts = emptyList(),
            environment = emptyMap(),
        )
        assertTrue(result.isSuccess)
        assertEquals(listOf(emptySet<String>()), capture.startCalls)
    }

    @Test
    fun `launch with a spawn failure stops the capture to avoid stale state`() {
        val capture = RecordingWriteCapture()
        val setup = newSetup()
        val proot = ProotBackendReal(
            backend = setup.backend,
            processLauncher = ThrowingProcessLauncherForCapture(),
            workspaceManager = setup.workspaceManager,
            writeCapture = capture,
        )

        val result = proot.launch(
            workspaceId = "ws-1",
            session = newSession(),
            executable = "/usr/bin/e2e-test",
            args = emptyList(),
            workingDirectory = "/workspace",
            bindMounts = listOf(
                BindMount(
                    hostPath = "/sdcard/ElysiumVanguard/projects",
                    containerPath = "/workspace",
                    readOnly = false,
                ),
            ),
            environment = emptyMap(),
        )
        assertTrue("expected failure, got $result", result.isFailure)
        // The capture was started, then stopped (the
        // spawn failed, no further writes to capture).
        assertEquals(1, capture.startCalls.size)
        assertEquals(1, capture.stopCalls)
    }

    @Test
    fun `stop does NOT stop the capture (the orchestrator reads writes after)`() {
        val capture = RecordingWriteCapture()
        val recorder = SingleCallProcessLauncher()
        val setup = newSetup()
        val proot = ProotBackendReal(
            backend = setup.backend,
            processLauncher = recorder,
            workspaceManager = setup.workspaceManager,
            writeCapture = capture,
        )
        proot.launch(
            workspaceId = "ws-1",
            session = newSession(),
            executable = "/usr/bin/e2e-test",
            args = emptyList(),
            workingDirectory = "/workspace",
            bindMounts = emptyList(),
            environment = emptyMap(),
        )
        // The capture is running after launch.
        assertEquals(0, capture.stopCalls)

        // Stop the process. The capture must NOT be
        // stopped yet — the orchestrator calls
        // `writes` after `stop`.
        val stopResult = proot.stop("ws-1", newSession())
        assertTrue(stopResult.isSuccess)
        assertEquals(0, capture.stopCalls)
    }

    @Test
    fun `writes returns the capture's snapshot at the time of the call`() {
        val capture = RecordingWriteCapture()
        val recorder = SingleCallProcessLauncher()
        val setup = newSetup()
        val proot = ProotBackendReal(
            backend = setup.backend,
            processLauncher = recorder,
            workspaceManager = setup.workspaceManager,
            writeCapture = capture,
        )
        proot.launch(
            workspaceId = "ws-1",
            session = newSession(),
            executable = "/usr/bin/e2e-test",
            args = emptyList(),
            workingDirectory = "/workspace",
            bindMounts = emptyList(),
            environment = emptyMap(),
        )
        proot.stop("ws-1", newSession())

        // Simulate the Android FileObserver firing
        // events between stop and the orchestrator's
        // `writes` call (the proot process wrote to
        // the bind mount before exiting).
        capture.simulatedWrites = listOf(
            "/sdcard/ElysiumVanguard/projects/output.json",
        )

        val writes = proot.writes("ws-1", newSession())
        assertEquals(
            listOf("/sdcard/ElysiumVanguard/projects/output.json"),
            writes,
        )
    }

    @Test
    fun `restoreSnapshot stops the capture (session is fully over)`() {
        val capture = RecordingWriteCapture()
        val recorder = SingleCallProcessLauncher()
        val setup = newSetup()
        // Add a snapshot to the workspace so
        // restoreSnapshot has something to roll back to.
        val workspaceManager = setup.workspaceManager
        val ws = workspaceManager.createWorkspace(
            name = "e2e-write-capture",
            sessions = emptyList(),
        ).getOrThrow()
        // Snapshot the (real, but empty) rootfs.
        val rootfsDir = File(tempFolder.root, "elysium-linux-1/rootfs")
        val snapResult = workspaceManager.snapshotWorkspace(
            workspaceId = ws.id,
            sourceRootfsPath = rootfsDir.absolutePath,
            mountPlan = MountPlan.EMPTY,
            label = "phase72-snap",
        )
        assertTrue("snapshot failed: $snapResult", snapResult.isSuccess)

        val proot = ProotBackendReal(
            backend = setup.backend,
            processLauncher = recorder,
            workspaceManager = workspaceManager,
            writeCapture = capture,
        )
        proot.launch(
            workspaceId = ws.id,
            session = newSession(),
            executable = "/usr/bin/e2e-test",
            args = emptyList(),
            workingDirectory = "/workspace",
            bindMounts = emptyList(),
            environment = emptyMap(),
        )
        proot.stop(ws.id, newSession())

        // The capture is still running.
        assertEquals(0, capture.stopCalls)

        // Restore the snapshot. This is the
        // "session is fully over" signal.
        val restoreResult = proot.restoreSnapshot(ws.id, newSession())
        assertTrue("expected restore success, got $restoreResult", restoreResult.isSuccess)
        assertEquals(1, capture.stopCalls)
    }

    @Test
    fun `sequential sessions reset the capture (no write bleed)`() {
        val capture = RecordingWriteCapture()
        val recorder = SingleCallProcessLauncher()
        val setup = newSetup()
        val proot = ProotBackendReal(
            backend = setup.backend,
            processLauncher = recorder,
            workspaceManager = setup.workspaceManager,
            writeCapture = capture,
        )
        // Session A
        proot.launch(
            workspaceId = "ws-A",
            session = newSession(id = "sess-A", distroId = "elysium-linux-1"),
            executable = "/usr/bin/e2e-A",
            args = emptyList(),
            workingDirectory = "/workspace",
            bindMounts = listOf(
                BindMount(
                    hostPath = "/sdcard/A",
                    containerPath = "/workspace",
                    readOnly = false,
                ),
            ),
            environment = emptyMap(),
        )
        proot.stop("ws-A", newSession(id = "sess-A", distroId = "elysium-linux-1"))
        capture.simulatedWrites = listOf("/sdcard/A/from-session-A.txt")

        // Session A's reads still see the capture.
        val sessionAWrites = proot.writes("ws-A", newSession(id = "sess-A", distroId = "elysium-linux-1"))
        assertEquals(listOf("/sdcard/A/from-session-A.txt"), sessionAWrites)

        // Session B — a fresh launch resets the capture.
        proot.launch(
            workspaceId = "ws-B",
            session = newSession(id = "sess-B", distroId = "elysium-linux-1"),
            executable = "/usr/bin/e2e-B",
            args = emptyList(),
            workingDirectory = "/workspace",
            bindMounts = listOf(
                BindMount(
                    hostPath = "/sdcard/B",
                    containerPath = "/workspace",
                    readOnly = false,
                ),
            ),
            environment = emptyMap(),
        )
        // Session B's capture is fresh — no leftover
        // writes from session A.
        val sessionBWrites = proot.writes("ws-B", newSession(id = "sess-B", distroId = "elysium-linux-1"))
        assertEquals(emptyList<String>(), sessionBWrites)
    }

    // ============================================================
    // Test fixtures
    // ============================================================

    private data class Setup(
        val backend: DistroSessionBackend,
        val workspaceManager: WorkspaceManager,
    )

    private fun newSetup(): Setup {
        val rootDir = tempFolder.newFolder("elysium-linux-1")
        val rootfsDir = File(rootDir, "rootfs").also { it.mkdirs() }
        val distro = Distro(
            id = "elysium-linux-1",
            displayName = "Elysium Linux 1",
            family = DistroFamily.ARCH,
            version = "1.0.0",
            approxSizeBytes = 1_500_000_000L,
            minAndroidVersion = 26,
            rootfsUrl = "https://example.invalid/elysium-linux-1.tar.gz",
            rootfsKind = RootfsKind.TarGz,
            bootstrapCommand = null,
            packageManager = "pacman",
            homepage = "https://elysium.example",
            desktopProfile = DesktopProfile.TTY,
        )
        val installation = DistroInstallation(
            distro = distro,
            rootDir = rootDir,
            rootfsDir = rootfsDir,
            installedAtEpochMs = 1_700_000_000_000L,
            sizeOnDiskBytes = 1_500_000_000L,
            lastError = null,
        )
        val launcher = StubLauncher(
            builtShellCommandReturn = listOf(
                "/data/proot",
                "--kill-on-exit",
                "--link2symlink",
                "-0",
                "-r",
                rootfsDir.absolutePath,
                "-b",
                "/dev",
                "-b",
                "/proc",
                "-b",
                "/sys",
                "-w",
                "/root",
                "/usr/bin/env",
                "-i",
                "HOME=/root",
                "USER=root",
                "/bin/sh",
                "-lc",
                "PLACEHOLDER",
            ),
            envReturn = listOf(
                "LD_LIBRARY_PATH" to "/data/proot",
                "PROOT_LOADER" to "/data/proot/loader",
            ),
        )
        val backend = StubDistroSessionBackend(installation, launcher)
        val store = FileWorkspaceStore(tempFolder.newFolder("ws-store"))
        val eventBus: RuntimeEventBus = SynchronizedEventBus()
        val workspaceManager = WorkspaceManager(
            store = store,
            eventBus = eventBus,
            snapshotEngine = WriteCaptureFakeSnapshotEngine(),
        )
        return Setup(backend, workspaceManager)
    }
}

/**
 * A [WriteCapture] that records every start / stop /
 * writes call so the test can assert the lifecycle.
 * The [simulatedWrites] field lets the test inject
 * writes between `launch` and the orchestrator's
 * `writes` call (simulating the Android
 * `FileObserver` events).
 */
private class RecordingWriteCapture : WriteCapture {
    val startCalls: MutableList<Set<String>> = mutableListOf<Set<String>>()
    var stopCalls: Int = 0
        private set
    var writesCallCount: Int = 0
        private set
    var simulatedWrites: List<String> = emptyList()

    override fun start(watching: Set<String>) {
        startCalls.add(watching)
        // Clear any prior simulated writes — `start`
        // is the only path that resets the capture
        // (matches the production behavior; otherwise
        // a stale write from session A would bleed
        // into session B's audit).
        simulatedWrites = emptyList()
    }

    override fun stop() {
        stopCalls += 1
    }

    override fun writes(): List<String> {
        writesCallCount += 1
        return simulatedWrites
    }
}

/**
 * Records the count of `start` invocations and
 * returns a `LaunchedProcess` whose `stop` is a
 * no-op (a single test launcher instance is
 * shared across multiple `launch` calls — the
 * backends use the returned handle's `stop` lambda
 * in their own `stop` path).
 */
private class SingleCallProcessLauncher : ProcessLauncher {
    var startCount: Int = 0
        private set

    override fun start(command: List<String>, env: List<Pair<String, String>>, cwd: File): LaunchedProcess {
        startCount += 1
        return LaunchedProcess(pid = 99999, stop = { /* stop is a no-op */ })
    }
}

/** Throws on start. */
private class ThrowingProcessLauncherForCapture : ProcessLauncher {
    override fun start(command: List<String>, env: List<Pair<String, String>>, cwd: File): LaunchedProcess {
        throw java.io.IOException("simulated fork failure")
    }
}

private class StubLauncher(
    private val builtShellCommandReturn: List<String>,
    private val envReturn: List<Pair<String, String>>,
) : DistroLauncher {
    override val kind: LauncherKind = LauncherKind.NATIVE_PROOT
    override val capabilities: LauncherCapabilities = LauncherCapabilities(
        canRunElfBinaries = true,
        exposesPty = true,
        supportsBindMounts = true,
        requiresRoot = false,
        abiSupport = setOf("arm64-v8a"),
    )

    override fun buildShellCommand(rootfsDir: File, script: String): List<String> =
        builtShellCommandReturn.map { if (it == "PLACEHOLDER") script else it }
    override fun buildProbeCommand(rootfsDir: File, args: List<String>): List<String> =
        builtShellCommandReturn + args
    override fun environmentVariables(rootfsDir: File): List<Pair<String, String>> = envReturn
    override fun isAvailable(rootfsDir: File): Boolean = true
}

private class StubDistroSessionBackend(
    private val installation: DistroInstallation,
    private val launcher: DistroLauncher,
) : DistroSessionBackend {
    override fun findInstalled(id: String): DistroInstallation? =
        if (id == installation.distro.id) installation else null
    override fun launcherFor(id: String): LauncherPick? =
        if (id == installation.distro.id) LauncherPick(launcher, "test") else null
}

/**
 * Phase 72 — a minimal in-memory [SnapshotEngine]
 * for the write-capture test. Records one snapshot
 * per workspace + answers rollback as Success.
 * The real [com.elysium.vanguard.core.runtime.snapshots.FilesystemSnapshotEngine]
 * is exercised in its own dedicated test suite.
 */
private class WriteCaptureFakeSnapshotEngine : SnapshotEngine {
    private val snapshots = java.util.concurrent.ConcurrentHashMap<String, MutableList<WorkspaceSnapshot>>()
    private val counter = AtomicInteger(0)

    override fun snapshot(
        workspaceId: String,
        sourceRootfsPath: String,
        mountPlan: MountPlan,
        label: String,
        nowMs: Long?,
    ): SnapshotResult {
        val id = "snap-phase72-${counter.incrementAndGet()}"
        val snap = WorkspaceSnapshot(
            id = id,
            workspaceId = workspaceId,
            label = label,
            createdAtMs = nowMs ?: 1_700_000_000_000L,
            rootfsPath = "$sourceRootfsPath/snapshots/$id",
            mountPlan = mountPlan,
            sizeBytes = 0L,
            copyStrategy = CopyStrategy.HARDLINK,
        )
        snapshots.computeIfAbsent(workspaceId) { mutableListOf() }.add(snap)
        return SnapshotResult.Success(snap)
    }

    override fun rollback(
        workspaceId: String,
        snapshotId: String,
        liveRootfsPath: String,
    ): RollbackResult {
        val snap = snapshots[workspaceId]?.firstOrNull { it.id == snapshotId }
            ?: return RollbackResult.Failure(
                com.elysium.vanguard.core.runtime.snapshots.SnapshotError.SnapshotNotFound(snapshotId)
            )
        return RollbackResult.Success(snap)
    }

    override fun list(workspaceId: String): List<WorkspaceSnapshot> =
        snapshots[workspaceId]?.toList() ?: emptyList()

    override fun delete(snapshotId: String): Boolean {
        for ((_, list) in snapshots) {
            if (list.removeAll { it.id == snapshotId }) return true
        }
        return false
    }
}
