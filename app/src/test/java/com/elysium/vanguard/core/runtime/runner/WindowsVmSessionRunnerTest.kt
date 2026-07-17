package com.elysium.vanguard.core.runtime.runner

import com.elysium.vanguard.core.runtime.observability.RecordingEventBus
import com.elysium.vanguard.core.runtime.observability.RuntimeEvent
import com.elysium.vanguard.core.runtime.windows.WindowsVmError
import com.elysium.vanguard.core.runtime.windows.WindowsVmState
import com.elysium.vanguard.core.runtime.workspaces.Workspace
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 31 — tests for the [WindowsVmSessionRunner].
 *
 * The runner is the parallel of [LinuxProotSessionRunner]
 * (Phase 30), but the "process" is a QEMU VM rather
 * than a forked shell. The tests pin:
 *
 *   - happy path: backend returns a `Running` state →
 *     `SessionState.Running(pid = qemuPid, port = qmpPort)`;
 *     a `SessionStartedEvent` is on the bus with
 *     `launcherKind = "QEMU"`.
 *   - the Booting case: backend returns `Booting` →
 *     `SessionState.Starting`; no `SessionStartedEvent`
 *     is published (the VM hasn't reached a live state).
 *   - error paths: backend refuses with `WindowsVmError`
 *     → `SessionStartFailedEvent` + `StartFailed` typed
 *     error.
 *   - wrong session kind (LinuxProot) → `UnsupportedKind`.
 *   - already running → `SessionAlreadyRunning`.
 *   - stop path: backend returns success → state
 *     becomes `Stopped`, a `SessionStoppedEvent` is
 *     on the bus, exit code = 0.
 *   - state + listActive + activeCount lookups.
 *   - thread-safety: 4 × 20 concurrent starts on
 *     disjoint sessions.
 */
class WindowsVmSessionRunnerTest {

    private val bus = RecordingEventBus()
    private val clock = AtomicLong(0L)

    // --- happy path ---

    @Test
    fun `start with a Running backend state transitions to SessionState Running and publishes SessionStartedEvent`() {
        val backend = FakeBackend()
            .onStart("win11-pro-23h2", WindowsVmState.Running(pid = 7777, qmpPort = 4444))
        val runner = WindowsVmSessionRunner(backend, bus, clock = clock::get)
        val (workspace, session) = workspaceWithWin("ws-1", "win11-pro-23h2", "s-1")

        clock.set(50L)
        val result = runner.start(workspace, session)

        assertTrue("start must succeed; failure: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val state = result.getOrThrow()
        assertTrue("state must be Running, was $state", state is SessionState.Running)
        val running = state as SessionState.Running
        assertEquals(7777, running.pid)
        assertEquals(4444, running.port)
        assertEquals(50L, running.startedAtMs)

        val started = bus.events.filterIsInstance<RuntimeEvent.SessionStartedEvent>().single()
        assertEquals(50L, started.atMs)
        assertEquals("ws-1", started.workspaceId)
        assertEquals("s-1", started.sessionId)
        assertEquals("WINDOWS_VM", started.kind)
        assertEquals("QEMU", started.launcherKind)
        assertEquals(7777, started.pid)
    }

    @Test
    fun `start with a Booting backend state transitions to SessionState Starting and does not publish SessionStartedEvent`() {
        val backend = FakeBackend()
            .onStart("win10-pro-22h2", WindowsVmState.Booting)
        val runner = WindowsVmSessionRunner(backend, bus, clock = clock::get)
        val (workspace, session) = workspaceWithWin("ws-1", "win10-pro-22h2", "s-1")

        val result = runner.start(workspace, session)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow() is SessionState.Starting)
        // No SessionStartedEvent is published while booting.
        assertEquals(0, bus.events.filterIsInstance<RuntimeEvent.SessionStartedEvent>().size)
    }

    @Test
    fun `start calls the backend with the session's spec id`() {
        val backend = FakeBackend()
            .onStart("server-2019", WindowsVmState.Running(pid = 1, qmpPort = 4444))
        val runner = WindowsVmSessionRunner(backend, bus, clock = clock::get)
        val (workspace, session) = workspaceWithWin("ws-1", "server-2019", "s-1")

        runner.start(workspace, session)

        assertEquals(1, backend.startCount.get())
        assertEquals("server-2019", backend.lastStartSpecId)
    }

    // --- error paths ---

    @Test
    fun `start rolls to Error and publishes SessionStartFailedEvent when the backend returns UnknownSpec`() {
        val backend = FakeBackend()
            .onStartError("unknown-spec", WindowsVmError.UnknownSpec("unknown-spec"))
        val runner = WindowsVmSessionRunner(backend, bus, clock = clock::get)
        val (workspace, session) = workspaceWithWin("ws-1", "unknown-spec", "s-1")

        val result = runner.start(workspace, session)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as SessionRunnerError.StartFailed
        assertTrue(error.causeMessage.contains("unknown-spec"))
        // The state rolled to Error.
        assertTrue(runner.state("ws-1", "s-1") is SessionState.Error)
        // A SessionStartFailedEvent was published.
        val failure = bus.events.filterIsInstance<RuntimeEvent.SessionStartFailedEvent>().single()
        assertEquals("ws-1", failure.workspaceId)
        assertEquals("s-1", failure.sessionId)
    }

    @Test
    fun `start returns UnsupportedKind for a LinuxProot session`() {
        val backend = FakeBackend()
        val runner = WindowsVmSessionRunner(backend, bus, clock = clock::get)
        val (workspace, _) = workspace("ws-1")
        val session = WorkspaceSession.LinuxProot(
            id = "linux-1",
            displayName = "Debian",
            distroId = "debian",
            profileId = "balanced"
        )

        val result = runner.start(workspace, session)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SessionRunnerError.UnsupportedKind)
        // The backend was not called.
        assertEquals(0, backend.startCount.get())
    }

    @Test
    fun `start returns SessionAlreadyRunning when the session is in a live state`() {
        val backend = FakeBackend()
            .onStart("win11-pro-23h2", WindowsVmState.Running(pid = 1, qmpPort = 4444))
        val runner = WindowsVmSessionRunner(backend, bus, clock = clock::get)
        val (workspace, session) = workspaceWithWin("ws-1", "win11-pro-23h2", "s-1")

        runner.start(workspace, session)
        val second = runner.start(workspace, session)

        assertTrue(second.isFailure)
        assertTrue(second.exceptionOrNull() is SessionRunnerError.SessionAlreadyRunning)
        // The backend's start was called only once.
        assertEquals(1, backend.startCount.get())
    }

    // --- stop ---

    @Test
    fun `stop transitions Running to Stopped and publishes SessionStoppedEvent`() {
        val backend = FakeBackend()
            .onStart("win11-pro-23h2", WindowsVmState.Running(pid = 1, qmpPort = 4444))
        val runner = WindowsVmSessionRunner(backend, bus, clock = clock::get)
        val (workspace, session) = workspaceWithWin("ws-1", "win11-pro-23h2", "s-1")

        runner.start(workspace, session)
        clock.set(80L)
        val result = runner.stop(workspace, session)

        assertTrue(result.isSuccess)
        assertEquals(SessionState.Stopped, result.getOrThrow())
        val stopped = bus.events.filterIsInstance<RuntimeEvent.SessionStoppedEvent>().single()
        assertEquals(80L, stopped.atMs)
        assertEquals("ws-1", stopped.workspaceId)
        assertEquals("s-1", stopped.sessionId)
        assertEquals(0, stopped.exitCode)
    }

    @Test
    fun `stop returns SessionNotRunning when the session is Idle`() {
        val backend = FakeBackend()
        val runner = WindowsVmSessionRunner(backend, bus, clock = clock::get)
        val (workspace, session) = workspaceWithWin("ws-1", "win11-pro-23h2", "s-1")

        val result = runner.stop(workspace, session)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SessionRunnerError.SessionNotRunning)
    }

    // --- state + listActive ---

    @Test
    fun `state returns Idle for an unknown session`() {
        val runner = WindowsVmSessionRunner(FakeBackend(), bus, clock = clock::get)
        assertEquals(SessionState.Idle, runner.state("ws-x", "s-x"))
    }

    @Test
    fun `listActive returns only Starting, Running, and Stopping sessions`() {
        val backend = FakeBackend()
            .onStart("a", WindowsVmState.Running(pid = 1, qmpPort = 4444))
            .onStart("b", WindowsVmState.Running(pid = 2, qmpPort = 4445))
        val runner = WindowsVmSessionRunner(backend, bus, clock = clock::get)
        val (ws, s1) = workspaceWithWin("ws-1", "a", "s-1")
        val (_, s2) = workspaceWithWin("ws-1", "b", "s-2")

        runner.start(ws, s1)
        runner.start(ws, s2)
        runner.stop(ws, s1)

        val active = runner.listActive()
        assertEquals(1, active.size)
        assertEquals("s-2", active.single().sessionId)
        assertEquals("QEMU", active.single().launcherKind)
    }

    @Test
    fun `activeCount tracks the size of listActive`() {
        val backend = FakeBackend()
            .onStart("a", WindowsVmState.Running(pid = 1, qmpPort = 4444))
        val runner = WindowsVmSessionRunner(backend, bus, clock = clock::get)
        val (ws, s1) = workspaceWithWin("ws-1", "a", "s-1")

        assertEquals(0, runner.activeCount())
        runner.start(ws, s1)
        assertEquals(1, runner.activeCount())
    }

    // --- thread safety ---

    @Test
    fun `start is thread-safe under concurrent calls on disjoint sessions`() {
        val backend = FakeBackend()
            .onStartForAnySpec(WindowsVmState.Running(pid = 9000, qmpPort = 4444))
        val runner = WindowsVmSessionRunner(backend, bus, clock = clock::get)

        val start = CountDownLatch(1)
        val done = CountDownLatch(4)
        repeat(4) { t ->
            Thread {
                start.await()
                repeat(20) { i ->
                    val (ws, session) = workspaceWithWin("ws-$t", "spec-$t-$i", "s-$t-$i")
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
        val session = windowsSession("$id-dummy", "spec")
        return Workspace(
            id = id,
            name = "Workspace $id",
            createdAtMs = 0L,
            sessions = listOf(session)
        ) to session
    }

    private fun workspaceWithWin(workspaceId: String, specId: String, sessionId: String): Pair<Workspace, WorkspaceSession> {
        val session = windowsSession(sessionId, specId)
        return Workspace(
            id = workspaceId,
            name = "Workspace $workspaceId",
            createdAtMs = 0L,
            sessions = listOf(session)
        ) to session
    }

    private fun windowsSession(id: String, specId: String): WorkspaceSession.WindowsVm =
        WorkspaceSession.WindowsVm(
            id = id,
            displayName = "Windows $id",
            windowsSpecId = specId
        )

    // --- fakes ---

    /**
     * A hand-rolled [WindowsVmSessionBackend] for tests. Holds
     * a per-spec start-result (a [Result] of [WindowsVmState]).
     * If no per-spec result is registered, returns a default
     * "Running(1, 4444)".
     */
    private class FakeBackend : WindowsVmSessionBackend {
        private val startResults = mutableMapOf<String, Result<WindowsVmState>>()
        private val defaultStartResult: Result<WindowsVmState> =
            Result.success(WindowsVmState.Running(pid = 1, qmpPort = 4444))
        val startCount = AtomicInteger(0)
        var lastStartSpecId: String? = null

        fun onStart(specId: String, state: WindowsVmState): FakeBackend {
            startResults[specId] = Result.success(state)
            return this
        }

        fun onStartError(specId: String, error: WindowsVmError): FakeBackend {
            startResults[specId] = Result.failure(error)
            return this
        }

        fun onStartForAnySpec(state: WindowsVmState): FakeBackend {
            // Replace all per-spec results; any spec falls
            // through to the same handler. Useful for
            // thread-safety tests.
            startResults.clear()
            // The runner will hit the default; the
            // default is what we want. We set it here
            // for clarity.
            return this
        }

        override fun startVm(specId: String): Result<WindowsVmState> {
            startCount.incrementAndGet()
            lastStartSpecId = specId
            return startResults[specId] ?: defaultStartResult
        }

        override fun stopVm(specId: String): Result<WindowsVmState> {
            // Idempotent: a stop on a stopped VM is success
            // (mirrors the real WindowsVmManager).
            return Result.success(WindowsVmState.Stopped)
        }

        override fun getState(specId: String): WindowsVmState = WindowsVmState.Stopped
    }
}
