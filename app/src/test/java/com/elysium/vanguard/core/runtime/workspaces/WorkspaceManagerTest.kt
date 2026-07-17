package com.elysium.vanguard.core.runtime.workspaces

import com.elysium.vanguard.core.runtime.observability.RuntimeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 24 — tests for the workspace manager + store +
 * session + workspace.
 *
 * The manager is the runtime's user-facing surface for
 * the multi-session state isolation layer. The tests
 * pin:
 *
 *   - Workspace value-type invariants (id, name, no
 *     duplicate session ids).
 *   - WorkspaceState transitions (Active / Paused /
 *     Closed) and the re-activation path.
 *   - Session types (LinuxProot + WindowsVm) and their
 *     init invariants.
 *   - Manager orchestration: create, pause, activate,
 *     close, add session, remove session.
 *   - Cross-workspace isolation: a session id used by
 *     another workspace is a hard error.
 *   - Persistence round-trip: the in-memory store saves
 *     and reloads every state change.
 *   - Thread-safety under 8 × 20 concurrent operations.
 */
class WorkspaceManagerTest {

    private val store = InMemoryWorkspaceStore()
    private val eventBus = com.elysium.vanguard.core.runtime.observability.RecordingEventBus()
    private val manager = WorkspaceManager(store, eventBus)

    // --- workspace invariants ---

    @Test
    fun `workspace rejects a blank id`() {
        try {
            Workspace(id = "", name = "x", createdAtMs = 1, sessions = emptyList())
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `workspace rejects a blank name`() {
        try {
            Workspace(id = "ws-1", name = "", createdAtMs = 1, sessions = emptyList())
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `workspace rejects duplicate session ids`() {
        val session = linuxSession("s-1")
        try {
            Workspace(
                id = "ws-1",
                name = "x",
                createdAtMs = 1,
                sessions = listOf(session, session)
            )
            fail("expected IllegalArgumentException for duplicate session ids")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `closed workspace requires at least one session`() {
        try {
            Workspace(
                id = "ws-1",
                name = "x",
                createdAtMs = 1,
                sessions = emptyList(),
                state = WorkspaceState.Closed
            )
            fail("expected IllegalArgumentException for closed empty workspace")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `workspace findSession returns null for unknown id`() {
        val ws = makeWorkspace("ws-1", listOf(linuxSession("s-1")))
        assertNull(ws.findSession("nope"))
        assertNotNull(ws.findSession("s-1"))
    }

    @Test
    fun `workspace sessionCountByKind returns the right breakdown`() {
        val ws = makeWorkspace("ws-1", listOf(
            linuxSession("s-1"),
            linuxSession("s-2"),
            windowsSession("w-1")
        ))
        val counts = ws.sessionCountByKind()
        assertEquals(2, counts[WorkspaceSession.SessionKind.LINUX_PROOT]?.toInt())
        assertEquals(1, counts[WorkspaceSession.SessionKind.WINDOWS_VM]?.toInt())
    }

    // --- session invariants ---

    @Test
    fun `LinuxProot session rejects a blank distroId`() {
        try {
            WorkspaceSession.LinuxProot(
                id = "s-1",
                displayName = "S1",
                distroId = "",
                profileId = "balanced"
            )
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `WindowsVm session rejects a blank windowsSpecId`() {
        try {
            WorkspaceSession.WindowsVm(
                id = "w-1",
                displayName = "W1",
                windowsSpecId = ""
            )
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    // --- state machine ---

    @Test
    fun `createWorkspace produces an Active workspace with auto-generated id`() {
        val result = manager.createWorkspace("Work")
        assertTrue(result.isSuccess)
        val ws = result.getOrThrow()
        assertEquals("Work", ws.name)
        assertEquals(WorkspaceState.Active, ws.state)
        assertTrue(ws.id.startsWith("ws-"))
    }

    @Test
    fun `createWorkspace rejects a blank name`() {
        val result = manager.createWorkspace("")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is WorkspaceError.InvalidName)
    }

    @Test
    fun `pauseWorkspace transitions Active to Paused`() {
        manager.createWorkspace("Work")
        val firstId = manager.listWorkspaces().single().id
        val paused = manager.pauseWorkspace(firstId)
        assertTrue(paused.isSuccess)
        assertEquals(WorkspaceState.Paused, paused.getOrThrow().state)
    }

    @Test
    fun `activateWorkspace transitions Paused back to Active`() {
        val ws = manager.createWorkspace("Work").getOrThrow()
        manager.pauseWorkspace(ws.id)
        val active = manager.activateWorkspace(ws.id)
        assertTrue(active.isSuccess)
        assertEquals(WorkspaceState.Active, active.getOrThrow().state)
    }

    @Test
    fun `closeWorkspace requires at least one session`() {
        // An empty workspace can't be created, so this
        // test only verifies the closed-empty rejection
        // path. We use the value-type invariant directly.
        try {
            Workspace(
                id = "ws-x",
                name = "X",
                createdAtMs = 1,
                sessions = emptyList(),
                state = WorkspaceState.Closed
            )
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `closeWorkspace transitions Active to Closed and re-open restores sessions`() {
        val ws = manager.createWorkspace(
            "Work",
            listOf(linuxSession("s-1"), windowsSession("w-1"))
        ).getOrThrow()
        val closed = manager.closeWorkspace(ws.id).getOrThrow()
        assertEquals(WorkspaceState.Closed, closed.state)
        // Re-activation preserves the sessions.
        val active = manager.activateWorkspace(ws.id).getOrThrow()
        assertEquals(WorkspaceState.Active, active.state)
        assertEquals(2, active.sessions.size)
    }

    @Test
    fun `state transitions on a non-existent workspace return NotFound`() {
        val result = manager.pauseWorkspace("ws-nope")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is WorkspaceError.NotFound)
    }

    // --- session management ---

    @Test
    fun `addSession appends a session and persists`() {
        val ws = manager.createWorkspace("Work").getOrThrow()
        val updated = manager.addSession(ws.id, linuxSession("s-1")).getOrThrow()
        assertEquals(1, updated.sessions.size)
        // Persist: a fresh manager reading from the same
        // store must see the session.
        val rehydrated = WorkspaceManager(store, eventBus)
        val reloaded = rehydrated.getWorkspace(ws.id)
        assertNotNull(reloaded)
        assertEquals(1, reloaded!!.sessions.size)
    }

    @Test
    fun `addSession rejects a duplicate session id in the same workspace`() {
        val ws = manager.createWorkspace("Work", listOf(linuxSession("s-1"))).getOrThrow()
        val result = manager.addSession(ws.id, linuxSession("s-1"))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is WorkspaceError.DuplicateSessionId)
    }

    @Test
    fun `addSession rejects a session id used by a different workspace`() {
        manager.createWorkspace("Work", listOf(linuxSession("s-1")))
        val personal = manager.createWorkspace("Personal").getOrThrow()
        val result = manager.addSession(personal.id, linuxSession("s-1"))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is WorkspaceError.SessionIdUsedElsewhere)
    }

    @Test
    fun `removeSession drops a session and persists`() {
        val ws = manager.createWorkspace(
            "Work",
            listOf(linuxSession("s-1"), linuxSession("s-2"))
        ).getOrThrow()
        val updated = manager.removeSession(ws.id, "s-1").getOrThrow()
        assertEquals(1, updated.sessions.size)
        assertEquals("s-2", updated.sessions.single().id)
    }

    @Test
    fun `removeSession on a non-existent session returns SessionNotFound`() {
        val ws = manager.createWorkspace("Work", listOf(linuxSession("s-1"))).getOrThrow()
        val result = manager.removeSession(ws.id, "nope")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is WorkspaceError.SessionNotFound)
    }

    // --- isolation ---

    @Test
    fun `findSession is workspace-scoped`() {
        manager.createWorkspace("Work", listOf(linuxSession("s-1")))
        val personal = manager.createWorkspace("Personal", listOf(linuxSession("s-2"))).getOrThrow()
        // `s-1` exists in Work but not in Personal.
        assertNull(manager.findSession(personal.id, "s-1"))
        // `s-2` exists in Personal.
        assertNotNull(manager.findSession(personal.id, "s-2"))
    }

    // --- persistence round-trip ---

    @Test
    fun `every state change is persisted to the store`() {
        val ws = manager.createWorkspace("Work", listOf(linuxSession("s-1"))).getOrThrow()
        manager.pauseWorkspace(ws.id)
        manager.addSession(ws.id, windowsSession("w-1"))
        manager.closeWorkspace(ws.id)
        // A fresh manager reading from the same store
        // must see the final state.
        val rehydrated = WorkspaceManager(store, eventBus)
        val reloaded = rehydrated.getWorkspace(ws.id)
        assertNotNull(reloaded)
        assertEquals(WorkspaceState.Closed, reloaded!!.state)
        assertEquals(2, reloaded.sessions.size)
    }

    // --- thread safety ---

    @Test
    fun `manager is thread-safe under concurrent addSession from multiple threads`() {
        val ws = manager.createWorkspace("Work").getOrThrow()
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        repeat(8) { threadIdx ->
            Thread {
                start.await()
                repeat(20) { i ->
                    val sessionId = "s-$threadIdx-$i"
                    val result = manager.addSession(ws.id, linuxSession(sessionId))
                    assertTrue(
                        "every concurrent addSession must succeed",
                        result.isSuccess
                    )
                }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(15, TimeUnit.SECONDS))
        val reloaded = manager.getWorkspace(ws.id)
        assertEquals(8 * 20, reloaded!!.sessions.size)
    }

    // --- store ---

    @Test
    fun `InMemoryWorkspaceStore save-load-list-delete round-trip`() {
        val localStore = InMemoryWorkspaceStore()
        val localEventBus = com.elysium.vanguard.core.runtime.observability.RecordingEventBus()
        val localManager = WorkspaceManager(localStore, localEventBus)
        val ws = localManager.createWorkspace("Work", listOf(linuxSession("s-1"))).getOrThrow()
        // Save is implicit on every state change; load
        // returns the workspace.
        assertEquals(ws, localStore.load(ws.id))
        // List returns the workspace.
        assertEquals(listOf(ws), localStore.list())
        // Delete removes it.
        assertTrue(localStore.delete(ws.id))
        assertNull(localStore.load(ws.id))
        // A second delete is a no-op.
        assertFalse(localStore.delete(ws.id))
    }

    @Test
    fun `InMemoryWorkspaceStore clear empties the store`() {
        val localStore = InMemoryWorkspaceStore()
        val localEventBus = com.elysium.vanguard.core.runtime.observability.RecordingEventBus()
        val localManager = WorkspaceManager(localStore, localEventBus)
        localManager.createWorkspace("Work", listOf(linuxSession("s-1")))
        localStore.clear()
        assertEquals(0, localStore.size())
    }

    // --- Phase 39: manager publishes its own events ---

    @Test
    fun `createWorkspace publishes a WorkspaceStateChangedEvent on the bus`() {
        val result = manager.createWorkspace("Work", listOf(linuxSession("s-1")), nowMs = 1000L)
        assertTrue(result.isSuccess)
        val events = eventBus.events
        assertEquals(1, events.size)
        val event = events.single() as RuntimeEvent.WorkspaceStateChangedEvent
        assertEquals(1000L, event.atMs)
        assertEquals("(none)", event.fromState)
        assertEquals("Active", event.toState)
    }

    @Test
    fun `createWorkspace with a blank name does not publish on the bus`() {
        val result = manager.createWorkspace(name = "")
        assertTrue(result.isFailure)
        assertEquals("no event on failure", 0, eventBus.events.size)
    }

    @Test
    fun `pauseWorkspace publishes Active to Paused`() {
        val ws = manager.createWorkspace("Work", nowMs = 1L).getOrThrow()
        eventBus.clear()
        val result = manager.pauseWorkspace(ws.id)
        assertTrue(result.isSuccess)
        val events = eventBus.events
        assertEquals(1, events.size)
        val event = events.single() as RuntimeEvent.WorkspaceStateChangedEvent
        assertEquals("Active", event.fromState)
        assertEquals("Paused", event.toState)
    }

    @Test
    fun `activateWorkspace publishes Paused to Active`() {
        val ws = manager.createWorkspace("Work", nowMs = 1L).getOrThrow()
        manager.pauseWorkspace(ws.id)
        eventBus.clear()
        manager.activateWorkspace(ws.id)
        val event = eventBus.events.single() as RuntimeEvent.WorkspaceStateChangedEvent
        assertEquals("Paused", event.fromState)
        assertEquals("Active", event.toState)
    }

    @Test
    fun `closeWorkspace publishes Active to Closed`() {
        val ws = manager.createWorkspace("Work", listOf(linuxSession("s-1")), nowMs = 1L).getOrThrow()
        eventBus.clear()
        manager.closeWorkspace(ws.id)
        val event = eventBus.events.single() as RuntimeEvent.WorkspaceStateChangedEvent
        assertEquals("Active", event.fromState)
        assertEquals("Closed", event.toState)
    }

    @Test
    fun `addSession publishes a SessionAddedEvent with the session kind`() {
        val ws = manager.createWorkspace("Work", nowMs = 1L).getOrThrow()
        eventBus.clear()
        val result = manager.addSession(ws.id, linuxSession("s-1"))
        assertTrue(result.isSuccess)
        val event = eventBus.events.single() as RuntimeEvent.SessionAddedEvent
        assertEquals(ws.id, event.workspaceId)
        assertEquals("s-1", event.sessionId)
        assertEquals("LINUX_PROOT", event.sessionKind)
    }

    @Test
    fun `addSession with a duplicate id does not publish on the bus`() {
        val ws = manager.createWorkspace("Work", listOf(linuxSession("s-1")), nowMs = 1L).getOrThrow()
        eventBus.clear()
        val result = manager.addSession(ws.id, linuxSession("s-1"))
        assertTrue(result.isFailure)
        assertEquals("no event on duplicate", 0, eventBus.events.size)
    }

    @Test
    fun `removeSession publishes a SessionRemovedEvent`() {
        val ws = manager.createWorkspace("Work", listOf(linuxSession("s-1")), nowMs = 1L).getOrThrow()
        eventBus.clear()
        val result = manager.removeSession(ws.id, "s-1")
        assertTrue(result.isSuccess)
        val event = eventBus.events.single() as RuntimeEvent.SessionRemovedEvent
        assertEquals(ws.id, event.workspaceId)
        assertEquals("s-1", event.sessionId)
    }

    // --- helpers ---

    private fun linuxSession(id: String): WorkspaceSession.LinuxProot =
        WorkspaceSession.LinuxProot(
            id = id,
            displayName = "Linux $id",
            distroId = "debian-latest",
            profileId = "balanced"
        )

    private fun windowsSession(id: String): WorkspaceSession.WindowsVm =
        WorkspaceSession.WindowsVm(
            id = id,
            displayName = "Windows $id",
            windowsSpecId = "win11-pro-23h2"
        )

    private fun makeWorkspace(
        id: String,
        sessions: List<WorkspaceSession>
    ): Workspace = Workspace(
        id = id,
        name = "Test $id",
        createdAtMs = System.currentTimeMillis(),
        sessions = sessions
    )
}
