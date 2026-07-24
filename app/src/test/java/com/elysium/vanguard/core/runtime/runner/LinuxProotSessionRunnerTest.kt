package com.elysium.vanguard.core.runtime.runner

import com.elysium.vanguard.core.runtime.distros.Distro
import com.elysium.vanguard.core.runtime.distros.DistroFamily
import com.elysium.vanguard.core.runtime.distros.DistroInstallation
import com.elysium.vanguard.core.runtime.distros.RootfsKind
import com.elysium.vanguard.core.runtime.distros.launcher.LauncherCapabilities
import com.elysium.vanguard.core.runtime.distros.launcher.LauncherKind
import com.elysium.vanguard.core.runtime.distros.launcher.LauncherPick
import com.elysium.vanguard.core.runtime.distros.launcher.DistroLauncher
import com.elysium.vanguard.core.runtime.observability.RecordingEventBus
import com.elysium.vanguard.core.runtime.observability.RuntimeEvent
import com.elysium.vanguard.core.runtime.workspaces.Workspace
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 30 — tests for the [LinuxProotSessionRunner].
 *
 * The runner is the orchestrator that turns a
 * `WorkspaceSession.LinuxProot` into a live host process. The
 * tests pin:
 *
 *   - the happy path: distro installed + launcher available +
 *     processLauncher returns a fake pid → state is
 *     `Running(pid)`, a `SessionStartedEvent` is on the bus.
 *   - error paths: missing distro, missing launcher, wrong
 *     session kind, already running, start failure, not
 *     running on stop.
 *   - the bus: every state transition publishes a typed
 *     runtime event with the right `atMs` / `workspaceId` /
 *     `sessionId` / `pid`.
 *   - thread-safety: 4 threads × 20 starts on disjoint
 *     sessions → 80 active sessions, zero lost writes.
 */
class LinuxProotSessionRunnerTest {

    private val bus = RecordingEventBus()
    private val clock = AtomicLong(0L)

    // --- happy path ---

    @Test
    fun `start transitions Idle to Starting to Running and publishes SessionStartedEvent`() {
        val backend = FakeBackend().withInstalled("debian", launcherKind = LauncherKind.JAILED_SHELL)
        val launcher = RecordingProcessLauncher(pid = 4242)
        val runner = LinuxProotSessionRunner(backend, launcher, bus, clock = clock::get)
        val (workspace, session) = workspaceWithLinux("ws-1", "debian", "s-1")

        clock.set(100L)
        val result = runner.start(workspace, session)

        assertTrue("start must succeed; failure: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val state = result.getOrThrow()
        assertTrue("state must be Running, was $state", state is SessionState.Running)
        val running = state as SessionState.Running
        assertEquals(4242, running.pid)
        assertEquals(100L, running.startedAtMs)

        val started = bus.events.filterIsInstance<RuntimeEvent.SessionStartedEvent>().single()
        assertEquals(100L, started.atMs)
        assertEquals("ws-1", started.workspaceId)
        assertEquals("s-1", started.sessionId)
        assertEquals("LINUX_PROOT", started.kind)
        assertEquals("JAILED_SHELL", started.launcherKind)
        assertEquals(4242, started.pid)
    }

    @Test
    fun `start passes the rootfs cwd and the launcher's command + env to the process launcher`() {
        val backend = FakeBackend().withInstalled("debian", launcherKind = LauncherKind.JAILED_SHELL)
        val launcher = RecordingProcessLauncher(pid = 1)
        val runner = LinuxProotSessionRunner(backend, launcher, bus, clock = clock::get)
        val (workspace, session) = workspaceWithLinux("ws-1", "debian", "s-1")

        runner.start(workspace, session)

        val recorded = launcher.lastCall
        assertNotNull(recorded)
        val (command, env, cwd) = recorded!!
        assertTrue("command must start with the shell, was $command", command.first() == "/system/bin/sh")
        assertEquals(backend.installationFor("debian")!!.rootfsDir, cwd)
        // The empty-script path returns `/system/bin/sh -i` for JAILED_SHELL.
        assertEquals(listOf("/system/bin/sh", "-i"), command)
        // No env vars are required for the jailed shell.
        assertTrue(env.isEmpty())
    }

    // --- error paths ---

    @Test
    fun `start returns DistroNotInstalled when the distro is not in the backend`() {
        val backend = FakeBackend()  // no installs
        val launcher = RecordingProcessLauncher(pid = 1)
        val runner = LinuxProotSessionRunner(backend, launcher, bus, clock = clock::get)
        val (workspace, session) = workspaceWithLinux("ws-1", "missing", "s-1")

        val result = runner.start(workspace, session)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as SessionRunnerError.DistroNotInstalled
        assertEquals("missing", error.distroId)
        // The state rolled to Error.
        assertTrue(runner.state("ws-1", "s-1") is SessionState.Error)
        // A SessionStartFailedEvent was published.
        assertEquals(1, bus.events.filterIsInstance<RuntimeEvent.SessionStartFailedEvent>().size)
    }

    @Test
    fun `start returns LauncherUnavailable when the backend has no launcher for the distro`() {
        val backend = FakeBackend().withInstalled("debian", launcherKind = null)  // installed but unhealthy
        val launcher = RecordingProcessLauncher(pid = 1)
        val runner = LinuxProotSessionRunner(backend, launcher, bus, clock = clock::get)
        val (workspace, session) = workspaceWithLinux("ws-1", "debian", "s-1")

        val result = runner.start(workspace, session)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SessionRunnerError.LauncherUnavailable)
    }

    @Test
    fun `start returns UnsupportedKind for a WindowsVm session`() {
        val backend = FakeBackend().withInstalled("debian", launcherKind = LauncherKind.JAILED_SHELL)
        val launcher = RecordingProcessLauncher(pid = 1)
        val runner = LinuxProotSessionRunner(backend, launcher, bus, clock = clock::get)
        val (workspace, _) = workspace("ws-1")
        val session = WorkspaceSession.WindowsVm(
            id = "win-1",
            displayName = "Win 11",
            windowsSpecId = "win11-pro-23h2"
        )

        val result = runner.start(workspace, session)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SessionRunnerError.UnsupportedKind)
    }

    @Test
    fun `start returns SessionAlreadyRunning when the session is in a live state`() {
        val backend = FakeBackend().withInstalled("debian", launcherKind = LauncherKind.JAILED_SHELL)
        val launcher = RecordingProcessLauncher(pid = 1)
        val runner = LinuxProotSessionRunner(backend, launcher, bus, clock = clock::get)
        val (workspace, session) = workspaceWithLinux("ws-1", "debian", "s-1")

        runner.start(workspace, session)
        val second = runner.start(workspace, session)

        assertTrue(second.isFailure)
        assertTrue(second.exceptionOrNull() is SessionRunnerError.SessionAlreadyRunning)
    }

    @Test
    fun `start returns StartFailed when the process launcher throws and publishes a failure event`() {
        val backend = FakeBackend().withInstalled("debian", launcherKind = LauncherKind.JAILED_SHELL)
        val launcher = ThrowingProcessLauncher(IOException("boom"))
        val runner = LinuxProotSessionRunner(backend, launcher, bus, clock = clock::get)
        val (workspace, session) = workspaceWithLinux("ws-1", "debian", "s-1")

        val result = runner.start(workspace, session)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as SessionRunnerError.StartFailed
        assertTrue(error.causeMessage.contains("boom"))
        // State rolled to Error.
        val state = runner.state("ws-1", "s-1")
        assertTrue(state is SessionState.Error)
        // The failure event was published.
        val failure = bus.events.filterIsInstance<RuntimeEvent.SessionStartFailedEvent>().single()
        assertEquals("ws-1", failure.workspaceId)
        assertEquals("s-1", failure.sessionId)
    }

    // --- stop ---

    @Test
    fun `stop transitions Running to Stopped and calls the process launcher's stop`() {
        val backend = FakeBackend().withInstalled("debian", launcherKind = LauncherKind.JAILED_SHELL)
        val launcher = RecordingProcessLauncher(pid = 1)
        val runner = LinuxProotSessionRunner(backend, launcher, bus, clock = clock::get)
        val (workspace, session) = workspaceWithLinux("ws-1", "debian", "s-1")

        runner.start(workspace, session)
        clock.set(200L)
        val result = runner.stop(workspace, session)

        assertTrue(result.isSuccess)
        assertEquals(SessionState.Stopped, result.getOrThrow())
        assertEquals(1, launcher.stopCount.get())
        // A SessionStoppedEvent was published.
        val stopped = bus.events.filterIsInstance<RuntimeEvent.SessionStoppedEvent>().single()
        assertEquals(200L, stopped.atMs)
        assertEquals("ws-1", stopped.workspaceId)
        assertEquals("s-1", stopped.sessionId)
    }

    @Test
    fun `stop returns SessionNotRunning when the session is Idle`() {
        val backend = FakeBackend()
        val launcher = RecordingProcessLauncher(pid = 1)
        val runner = LinuxProotSessionRunner(backend, launcher, bus, clock = clock::get)
        val (workspace, session) = workspaceWithLinux("ws-1", "debian", "s-1")

        val result = runner.stop(workspace, session)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SessionRunnerError.SessionNotRunning)
    }

    // --- PHASE 119 — real waitFor on stop() ---

    @Test
    fun `stop reads the real OS exit code from the process launcher's waitFor and publishes it`() {
        val backend = FakeBackend().withInstalled("debian", launcherKind = LauncherKind.JAILED_SHELL)
        // Simulate a process that exited cleanly via `proot --help2map`
        // style success — the real OS exit code is 0.
        val launcher = RecordingProcessLauncher(pid = 1, waitForExitCode = 0)
        val runner = LinuxProotSessionRunner(backend, launcher, bus, clock = clock::get)
        val (workspace, session) = workspaceWithLinux("ws-1", "debian", "s-1")

        runner.start(workspace, session)
        runner.stop(workspace, session)

        val stopped = bus.events.filterIsInstance<RuntimeEvent.SessionStoppedEvent>().single()
        assertEquals(0, stopped.exitCode)
        // waitFor was consulted exactly once for the OS exit code.
        assertEquals(1, launcher.waitForCount.get())
    }

    @Test
    fun `stop propagates a non-zero exit code (SIGTERM = 143) from waitFor`() {
        val backend = FakeBackend().withInstalled("debian", launcherKind = LauncherKind.JAILED_SHELL)
        // 128 + 15 (SIGTERM) = 143 — the typical exit code a
        // proot session emits when killed by a stop signal.
        val launcher = RecordingProcessLauncher(pid = 4242, waitForExitCode = 143)
        val runner = LinuxProotSessionRunner(backend, launcher, bus, clock = clock::get)
        val (workspace, session) = workspaceWithLinux("ws-1", "debian", "s-1")

        runner.start(workspace, session)
        runner.stop(workspace, session)

        val stopped = bus.events.filterIsInstance<RuntimeEvent.SessionStoppedEvent>().single()
        assertEquals(143, stopped.exitCode)
        assertEquals(1, launcher.waitForCount.get())
    }

    @Test
    fun `stop propagates an unusual exit code verbatim (137 = SIGKILL)`() {
        val backend = FakeBackend().withInstalled("debian", launcherKind = LauncherKind.JAILED_SHELL)
        val launcher = RecordingProcessLauncher(pid = 7, waitForExitCode = 137)
        val runner = LinuxProotSessionRunner(backend, launcher, bus, clock = clock::get)
        val (workspace, session) = workspaceWithLinux("ws-1", "debian", "s-1")

        runner.start(workspace, session)
        runner.stop(workspace, session)

        val stopped = bus.events.filterIsInstance<RuntimeEvent.SessionStoppedEvent>().single()
        // 128 + 9 (SIGKILL) = 137 — verbatim from the OS, no
        // translation, no clamping. Pre-Phase-119 the runner
        // hardcoded `0` here.
        assertEquals(137, stopped.exitCode)
    }

    @Test
    fun `stop falls back to exit code -1 when waitFor throws and still publishes the event`() {
        val backend = FakeBackend().withInstalled("debian", launcherKind = LauncherKind.JAILED_SHELL)
        // A ProcessLauncher that supplies a waitFor callback
        // which throws — simulates an OS that loses the pid
        // mid-stop (e.g. a process already reaped by a sibling
        // signal handler). The runner must NOT crash; it must
        // publish a sentinel exit code so the operator sees the
        // anomaly.
        val launcher = ThrowingWaitForProcessLauncher(pid = 1, exception = IllegalStateException("pid reaped"))
        val runner = LinuxProotSessionRunner(backend, launcher, bus, clock = clock::get)
        val (workspace, session) = workspaceWithLinux("ws-1", "debian", "s-1")

        runner.start(workspace, session)
        val result = runner.stop(workspace, session)

        // The stop itself succeeded (the runner caught the
        // waitFor throw and fell back).
        assertTrue("stop must succeed even when waitFor throws; failure: ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals(SessionState.Stopped, result.getOrThrow())
        val stopped = bus.events.filterIsInstance<RuntimeEvent.SessionStoppedEvent>().single()
        assertEquals(-1, stopped.exitCode)
    }

    @Test
    fun `a session can be restarted after stop`() {
        val backend = FakeBackend().withInstalled("debian", launcherKind = LauncherKind.JAILED_SHELL)
        val launcher = RecordingProcessLauncher(pid = 1)
        val runner = LinuxProotSessionRunner(backend, launcher, bus, clock = clock::get)
        val (workspace, session) = workspaceWithLinux("ws-1", "debian", "s-1")

        runner.start(workspace, session)
        runner.stop(workspace, session)
        // After stop, state is Startable again.
        assertTrue(runner.state("ws-1", "s-1").isStartable())
        val second = runner.start(workspace, session)
        assertTrue(second.isSuccess)
    }

    // --- state + listActive ---

    @Test
    fun `state returns Idle for an unknown session`() {
        val runner = LinuxProotSessionRunner(FakeBackend(), RecordingProcessLauncher(1), bus, clock = clock::get)
        assertEquals(SessionState.Idle, runner.state("ws-x", "s-x"))
    }

    @Test
    fun `listActive returns only Starting, Running, and Stopping sessions`() {
        val backend = FakeBackend().withInstalled("debian", launcherKind = LauncherKind.JAILED_SHELL)
        val launcher = RecordingProcessLauncher(pid = 1)
        val runner = LinuxProotSessionRunner(backend, launcher, bus, clock = clock::get)
        val (ws, s1) = workspaceWithLinux("ws-1", "debian", "s-1")
        val (_, s2) = workspaceWithLinux("ws-1", "debian", "s-2")

        runner.start(ws, s1)  // Running
        runner.start(ws, s2)  // Running
        runner.stop(ws, s1)   // Stopped → not in listActive

        val active = runner.listActive()
        assertEquals(1, active.size)
        assertEquals("s-2", active.single().sessionId)
    }

    @Test
    fun `activeCount tracks the size of listActive`() {
        val backend = FakeBackend().withInstalled("debian", launcherKind = LauncherKind.JAILED_SHELL)
        val launcher = RecordingProcessLauncher(pid = 1)
        val runner = LinuxProotSessionRunner(backend, launcher, bus, clock = clock::get)
        val (ws, s1) = workspaceWithLinux("ws-1", "debian", "s-1")
        val (_, s2) = workspaceWithLinux("ws-1", "debian", "s-2")

        assertEquals(0, runner.activeCount())
        runner.start(ws, s1)
        assertEquals(1, runner.activeCount())
        runner.start(ws, s2)
        assertEquals(2, runner.activeCount())
        runner.stop(ws, s1)
        assertEquals(1, runner.activeCount())
    }

    // --- thread safety ---

    @Test
    fun `start is thread-safe under concurrent calls on disjoint sessions`() {
        val backend = FakeBackend().withInstalled("debian", launcherKind = LauncherKind.JAILED_SHELL)
        val launcher = RecordingProcessLauncher(pid = 1000)
        val runner = LinuxProotSessionRunner(backend, launcher, bus, clock = clock::get)

        val start = CountDownLatch(1)
        val done = CountDownLatch(4)
        repeat(4) { t ->
            Thread {
                start.await()
                repeat(20) { i ->
                    val (ws, session) = workspaceWithLinux("ws-$t", "debian", "s-$t-$i")
                    val result = runner.start(ws, session)
                    assertTrue("every start must succeed", result.isSuccess)
                }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(15, TimeUnit.SECONDS))
        // 4 × 20 = 80 active sessions, no lost writes.
        assertEquals(80, runner.listActive().size)
    }

    // --- helpers ---

    private fun workspace(id: String): Pair<Workspace, WorkspaceSession> {
        // Return a dummy session; tests that don't use it
        // construct their own.
        val session = linuxSession("$id-dummy")
        return Workspace(
            id = id,
            name = "Workspace $id",
            createdAtMs = 0L,
            sessions = listOf(session)
        ) to session
    }

    private fun workspaceWithLinux(workspaceId: String, distroId: String, sessionId: String): Pair<Workspace, WorkspaceSession> {
        val session = linuxSession(sessionId, distroId)
        return Workspace(
            id = workspaceId,
            name = "Workspace $workspaceId",
            createdAtMs = 0L,
            sessions = listOf(session)
        ) to session
    }

    private fun linuxSession(id: String, distroId: String = "debian"): WorkspaceSession.LinuxProot =
        WorkspaceSession.LinuxProot(
            id = id,
            displayName = "Linux $id",
            distroId = distroId,
            profileId = "balanced"
        )

    // --- fakes ---

    /**
     * A hand-rolled [DistroSessionBackend] for tests. Tracks a
     * per-distro-id install + launcher pair; returns null for
     * any id that is not registered.
     */
    private class FakeBackend : DistroSessionBackend {
        private val installs = mutableMapOf<String, FakeInstall>()

        data class FakeInstall(
            val rootfsDir: File,
            val launcherKind: LauncherKind?
        )

        fun withInstalled(distroId: String, launcherKind: LauncherKind?): FakeBackend {
            installs[distroId] = FakeInstall(
                rootfsDir = Files.createTempDirectory("elysium-fake-rootfs").toFile(),
                launcherKind = launcherKind
            )
            return this
        }

        fun installationFor(distroId: String): FakeInstall? = installs[distroId]

        override fun findInstalled(id: String): DistroInstallation? {
            val install = installs[id] ?: return null
            return DistroInstallation(
                distro = Distro(
                    id = id,
                    displayName = id,
                    family = DistroFamily.DEBIAN,
                    version = "1.0",
                    approxSizeBytes = 0L,
                    minAndroidVersion = 26,
                    rootfsUrl = "https://example.invalid/$id.tar.gz",
                    rootfsKind = RootfsKind.TarGz,
                    bootstrapCommand = null,
                    packageManager = "apt",
                    homepage = "https://example.invalid/$id"
                ),
                rootDir = install.rootfsDir.parentFile ?: install.rootfsDir,
                rootfsDir = install.rootfsDir,
                installedAtEpochMs = 0L,
                sizeOnDiskBytes = 0L,
                lastError = null
            )
        }

        override fun launcherFor(id: String): LauncherPick? {
            val install = installs[id] ?: return null
            val kind = install.launcherKind ?: return null
            val launcher = object : DistroLauncher {
                override val kind: LauncherKind = kind
                override val capabilities: LauncherCapabilities = LauncherCapabilities.JAILED_BASELINE
                override fun buildShellCommand(rootfsDir: File, script: String): List<String> =
                    listOf("/system/bin/sh", "-i")
                override fun buildProbeCommand(rootfsDir: File, args: List<String>): List<String> =
                    listOf("/system/bin/sh", "-c", args.joinToString(" "))
                override fun isAvailable(rootfsDir: File): Boolean = true
            }
            return LauncherPick(launcher = launcher, reason = "test fixture")
        }
    }

    /**
     * A [ProcessLauncher] that records the last call and returns
     * a [LaunchedProcess] with the configured pid. The stop
     * callback increments [stopCount]; the waitFor callback
     * returns [waitForExitCode] (default `0` so existing tests
     * that don't care about exit code keep passing).
     *
     * PHASE 119 — `waitForCount` records how many times the
     * runner's `stop()` calls `waitFor` so tests can assert the
     * runner only consults the OS exit code once per session.
     */
    private class RecordingProcessLauncher(
        val pid: Int,
        val waitForExitCode: Int = 0,
    ) : ProcessLauncher {
        val stopCount = AtomicInteger(0)
        val waitForCount = AtomicInteger(0)
        var lastCall: Triple<List<String>, List<Pair<String, String>>, File>? = null

        override fun start(command: List<String>, env: List<Pair<String, String>>, cwd: File): LaunchedProcess {
            lastCall = Triple(command, env, cwd)
            return LaunchedProcess(
                pid = pid,
                stop = { stopCount.incrementAndGet() },
                waitFor = {
                    waitForCount.incrementAndGet()
                    waitForExitCode
                },
            )
        }
    }

    /**
     * A [ProcessLauncher] that always throws — used to test the
     * `StartFailed` error path.
     */
    private class ThrowingProcessLauncher(val toThrow: Throwable) : ProcessLauncher {
        override fun start(command: List<String>, env: List<Pair<String, String>>, cwd: File): LaunchedProcess {
            throw toThrow
        }
    }

    /**
     * A [ProcessLauncher] whose [LaunchedProcess.waitFor]
     * callback throws on invocation. Used to test the
     * Phase 119 fallback that swallows a waitFor throw and
     * publishes a `-1` exit code instead of crashing the
     * runner.
     */
    private class ThrowingWaitForProcessLauncher(
        val pid: Int,
        val exception: Throwable,
    ) : ProcessLauncher {
        val stopCount = AtomicInteger(0)

        override fun start(command: List<String>, env: List<Pair<String, String>>, cwd: File): LaunchedProcess {
            return LaunchedProcess(
                pid = pid,
                stop = { stopCount.incrementAndGet() },
                waitFor = { throw exception },
            )
        }
    }
}
