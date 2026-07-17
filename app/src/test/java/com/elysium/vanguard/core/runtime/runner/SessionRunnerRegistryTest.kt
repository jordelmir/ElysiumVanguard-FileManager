package com.elysium.vanguard.core.runtime.runner

import com.elysium.vanguard.core.runtime.workspaces.Workspace
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 32 — tests for [SessionRunnerRegistry].
 *
 * The registry is a thin facade that dispatches by
 * [WorkspaceSession.kind]. The tests pin:
 *
 *   - `start` with a `LinuxProot` session routes to
 *     the linux runner, NOT the windows runner.
 *   - `start` with a `WindowsVm` session routes to
 *     the windows runner, NOT the linux runner.
 *   - `stop` routes by kind.
 *   - `state` consults both runners; the first
 *     non-Idle wins.
 *   - `listActive` merges the two runners' active
 *     lists, sorted by `startedAtMs` ascending.
 *   - thread-safety: 4 threads × 20 mixed-kind
 *     starts → 80 active sessions, all routed
 *     correctly.
 */
class SessionRunnerRegistryTest {

    // --- dispatch ---

    @Test
    fun `start with a LinuxProot session routes to the linux runner`() {
        val linux = RecordingRunner(SessionState.Running(pid = 11, startedAtMs = 100L))
        val windows = RecordingRunner(SessionState.Running(pid = 99, startedAtMs = 100L))
        val registry = SessionRunnerRegistry(linux, windows)
        val (workspace, session) = workspaceWithLinux("ws-1", "debian", "s-1")

        val result = registry.start(workspace, session)

        assertTrue(result.isSuccess)
        assertEquals(1, linux.startCalls.size)
        assertEquals(0, windows.startCalls.size)
        assertEquals("s-1", linux.startCalls.single().second.id)
    }

    @Test
    fun `start with a WindowsVm session routes to the windows runner`() {
        val linux = RecordingRunner(SessionState.Running(pid = 11, startedAtMs = 100L))
        val windows = RecordingRunner(SessionState.Running(pid = 99, startedAtMs = 100L))
        val registry = SessionRunnerRegistry(linux, windows)
        val (workspace, session) = workspaceWithWin("ws-1", "win11", "s-1")

        val result = registry.start(workspace, session)

        assertTrue(result.isSuccess)
        assertEquals(0, linux.startCalls.size)
        assertEquals(1, windows.startCalls.size)
        assertEquals("s-1", windows.startCalls.single().second.id)
    }

    @Test
    fun `stop with a LinuxProot session routes to the linux runner`() {
        val linux = RecordingRunner(SessionState.Stopped)
        val windows = RecordingRunner(SessionState.Stopped)
        val registry = SessionRunnerRegistry(linux, windows)
        val (workspace, session) = workspaceWithLinux("ws-1", "debian", "s-1")

        registry.stop(workspace, session)

        assertEquals(1, linux.stopCalls.size)
        assertEquals(0, windows.stopCalls.size)
    }

    @Test
    fun `stop with a WindowsVm session routes to the windows runner`() {
        val linux = RecordingRunner(SessionState.Stopped)
        val windows = RecordingRunner(SessionState.Stopped)
        val registry = SessionRunnerRegistry(linux, windows)
        val (workspace, session) = workspaceWithWin("ws-1", "win11", "s-1")

        registry.stop(workspace, session)

        assertEquals(0, linux.stopCalls.size)
        assertEquals(1, windows.stopCalls.size)
    }

    // --- state ---

    @Test
    fun `state returns Idle when neither runner has the session`() {
        val registry = SessionRunnerRegistry(
            RecordingRunner(SessionState.Idle),
            RecordingRunner(SessionState.Idle)
        )
        assertEquals(SessionState.Idle, registry.state("ws-x", "s-x"))
    }

    @Test
    fun `state prefers the linux runner's view when it is non-Idle`() {
        val linux = RecordingRunner(SessionState.Running(pid = 1, startedAtMs = 1L))
        val windows = RecordingRunner(SessionState.Running(pid = 2, startedAtMs = 1L))
        val registry = SessionRunnerRegistry(linux, windows)

        val state = registry.state("ws-1", "s-1")

        assertTrue(state is SessionState.Running)
        assertEquals(1, (state as SessionState.Running).pid)
    }

    @Test
    fun `state falls back to the windows runner when the linux runner is Idle`() {
        val linux = RecordingRunner(SessionState.Idle)
        val windows = RecordingRunner(SessionState.Running(pid = 2, startedAtMs = 1L))
        val registry = SessionRunnerRegistry(linux, windows)

        val state = registry.state("ws-1", "s-1")

        assertTrue(state is SessionState.Running)
        assertEquals(2, (state as SessionState.Running).pid)
    }

    // --- listActive ---

    @Test
    fun `listActive merges both runners and sorts by startedAtMs ascending`() {
        val linux = RecordingRunner(SessionState.Idle).apply {
            activeSessions.add(ActiveSession("ws-1", "s-late", WorkspaceSession.SessionKind.LINUX_PROOT, SessionState.Running(pid = 1, startedAtMs = 200L), "JAILED"))
            activeSessions.add(ActiveSession("ws-1", "s-early", WorkspaceSession.SessionKind.LINUX_PROOT, SessionState.Running(pid = 1, startedAtMs = 100L), "JAILED"))
        }
        val windows = RecordingRunner(SessionState.Idle).apply {
            activeSessions.add(ActiveSession("ws-1", "s-mid", WorkspaceSession.SessionKind.WINDOWS_VM, SessionState.Running(pid = 1, startedAtMs = 150L), "QEMU"))
        }
        val registry = SessionRunnerRegistry(linux, windows)

        val active = registry.listActive()

        assertEquals(listOf("s-early", "s-mid", "s-late"), active.map { it.sessionId })
    }

    @Test
    fun `activeCount matches listActive size`() {
        val linux = RecordingRunner(SessionState.Idle).apply {
            activeSessions.add(ActiveSession("ws-1", "s-1", WorkspaceSession.SessionKind.LINUX_PROOT, SessionState.Running(pid = 1, startedAtMs = 1L), null))
        }
        val windows = RecordingRunner(SessionState.Idle)
        val registry = SessionRunnerRegistry(linux, windows)

        assertEquals(1, registry.activeCount())
    }

    // --- thread safety ---

    @Test
    fun `start is thread-safe under concurrent calls with mixed kinds`() {
        val linux = RecordingRunner(SessionState.Running(pid = 1, startedAtMs = 0L))
        val windows = RecordingRunner(SessionState.Running(pid = 1, startedAtMs = 0L))
        val registry = SessionRunnerRegistry(linux, windows)

        val start = CountDownLatch(1)
        val done = CountDownLatch(4)
        repeat(4) { t ->
            Thread {
                start.await()
                repeat(20) { i ->
                    val kind = if ((t + i) % 2 == 0) "linux" else "win"
                    val (workspace, session) = if (kind == "linux") {
                        workspaceWithLinux("ws-$t", "debian", "s-$t-$i")
                    } else {
                        workspaceWithWin("ws-$t", "win11", "s-$t-$i")
                    }
                    val result = registry.start(workspace, session)
                    assertTrue("every start must succeed", result.isSuccess)
                }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(15, TimeUnit.SECONDS))
        // 4 × 20 = 80 total starts, split across both runners.
        assertEquals(80, linux.startCalls.size + windows.startCalls.size)
        // All starts went somewhere; the merged active list is the same size.
        assertEquals(80, registry.listActive().size)
    }

    // --- helpers ---

    private fun workspaceWithLinux(workspaceId: String, distroId: String, sessionId: String): Pair<Workspace, WorkspaceSession> {
        val session = WorkspaceSession.LinuxProot(
            id = sessionId,
            displayName = "Linux $sessionId",
            distroId = distroId,
            profileId = "balanced"
        )
        return Workspace(
            id = workspaceId,
            name = "Workspace $workspaceId",
            createdAtMs = 0L,
            sessions = listOf(session)
        ) to session
    }

    private fun workspaceWithWin(workspaceId: String, specId: String, sessionId: String): Pair<Workspace, WorkspaceSession> {
        val session = WorkspaceSession.WindowsVm(
            id = sessionId,
            displayName = "Windows $sessionId",
            windowsSpecId = specId
        )
        return Workspace(
            id = workspaceId,
            name = "Workspace $workspaceId",
            createdAtMs = 0L,
            sessions = listOf(session)
        ) to session
    }

    /**
     * A hand-rolled [SessionRunner] for tests. Records
     * every start / stop call and returns the
     * configured state for `start` and `state`. The
     * `activeSessions` list is exposed so the test
     * can stage "this runner has N active sessions".
     */
    private class RecordingRunner(private val stateForStart: SessionState) : SessionRunner {
        val startCalls = mutableListOf<Pair<Workspace, WorkspaceSession>>()
        val stopCalls = mutableListOf<Pair<Workspace, WorkspaceSession>>()
        val stateMap = ConcurrentHashMap<String, SessionState>()
        val activeSessions = mutableListOf<ActiveSession>()

        override fun start(workspace: Workspace, session: WorkspaceSession): Result<SessionState> {
            startCalls += workspace to session
            stateMap[session.id] = stateForStart
            if (stateForStart.isLive()) {
                activeSessions += ActiveSession(
                    workspaceId = workspace.id,
                    sessionId = session.id,
                    kind = session.kind,
                    state = stateForStart,
                    launcherKind = null
                )
            }
            return Result.success(stateForStart)
        }

        override fun stop(workspace: Workspace, session: WorkspaceSession): Result<SessionState> {
            stopCalls += workspace to session
            stateMap[session.id] = SessionState.Stopped
            activeSessions.removeAll { it.sessionId == session.id }
            return Result.success(SessionState.Stopped)
        }

        override fun state(workspaceId: String, sessionId: String): SessionState =
            stateMap[sessionId] ?: stateForStart

        override fun listActive(): List<ActiveSession> = activeSessions.toList()
    }
}
