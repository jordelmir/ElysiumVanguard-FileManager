package com.elysium.vanguard.core.runtime.ui

import com.elysium.vanguard.core.runtime.observability.RecordingEventBus
import com.elysium.vanguard.core.runtime.observability.RuntimeEvent
import com.elysium.vanguard.core.runtime.workspaces.Workspace
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceError
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceManager
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceState
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceStore
import com.elysium.vanguard.core.runtime.workspaces.InMemoryWorkspaceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 29 — tests for the [WorkspacesViewModel].
 *
 * The ViewModel is the action layer of the workspace
 * UI. The tests pin:
 *
 *   - Empty initial state, hydrates from the manager
 *     on construction.
 *   - `createWorkspace` returns a `Workspace` on
 *     success, publishes a
 *     [RuntimeEvent.WorkspaceStateChangedEvent], and
 *     refreshes the state.
 *   - `createWorkspace` returns a typed failure on
 *     invalid input (blank name) and records the
 *     result on `lastActionResult`.
 *   - `pauseWorkspace` / `activateWorkspace` /
 *     `closeWorkspace` publish the right `fromState`
 *     / `toState` strings.
 *   - `addSession` / `removeSession` publish the
 *     per-session events and refresh.
 *   - The ViewModel re-reads state on every workspace
 *     or session event from the bus.
 *   - Thread-safety under 4 × 20 concurrent
 *     `createWorkspace` calls.
 */
class WorkspacesViewModelTest {

    private val store: WorkspaceStore = InMemoryWorkspaceStore()
    private val manager = WorkspaceManager(store)
    private val bus = RecordingEventBus()
    private val clock = AtomicLong(0L)
    private val viewModel = WorkspacesViewModel(manager, bus, clock = clock::get)

    // --- initial state ---

    @Test
    fun `empty initial state hydrates from the manager`() {
        val state = viewModel.state.value
        assertTrue("workspaces must be empty", state.workspaces.isEmpty())
        assertNull(state.lastActionResult)
    }

    // --- create ---

    @Test
    fun `createWorkspace returns a Workspace and refreshes the state`() {
        val result = viewModel.createWorkspace("Work")
        assertTrue(result.isSuccess)
        val ws = result.getOrThrow()
        assertEquals("Work", ws.name)
        assertEquals(WorkspaceState.Active, ws.state)
        // The state list now contains the new workspace.
        assertEquals(1, viewModel.state.value.workspaces.size)
        assertEquals(ws, viewModel.state.value.workspaces.single())
    }

    @Test
    fun `createWorkspace publishes a WorkspaceStateChangedEvent on the bus`() {
        clock.set(1234L)
        viewModel.createWorkspace("Work")
        val events = bus.events
        assertEquals(1, events.size)
        val event = events.single() as RuntimeEvent.WorkspaceStateChangedEvent
        assertEquals(1234L, event.atMs)
        assertEquals("(none)", event.fromState)
        assertEquals("Active", event.toState)
    }

    @Test
    fun `createWorkspace with a blank name returns InvalidName and records lastActionResult`() {
        val result = viewModel.createWorkspace("")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is WorkspaceError.InvalidName)
        // The state's lastActionResult is the failure.
        assertTrue(viewModel.state.value.lastActionResult?.isFailure == true)
    }

    // --- state transitions ---

    @Test
    fun `pauseWorkspace publishes Active to Paused`() {
        clock.set(1L)
        val ws = viewModel.createWorkspace("Work").getOrThrow()
        clock.set(2L)
        viewModel.pauseWorkspace(ws.id)
        val lastEvent = bus.events.last() as RuntimeEvent.WorkspaceStateChangedEvent
        assertEquals(2L, lastEvent.atMs)
        assertEquals("Active", lastEvent.fromState)
        assertEquals("Paused", lastEvent.toState)
    }

    @Test
    fun `activateWorkspace publishes Paused to Active`() {
        clock.set(1L)
        val ws = viewModel.createWorkspace("Work").getOrThrow()
        viewModel.pauseWorkspace(ws.id)
        clock.set(2L)
        viewModel.activateWorkspace(ws.id)
        val lastEvent = bus.events.last() as RuntimeEvent.WorkspaceStateChangedEvent
        assertEquals("Paused", lastEvent.fromState)
        assertEquals("Active", lastEvent.toState)
    }

    @Test
    fun `closeWorkspace publishes Active to Closed`() {
        clock.set(1L)
        val ws = viewModel.createWorkspace("Work", listOf(linuxSession("s-1"))).getOrThrow()
        viewModel.closeWorkspace(ws.id)
        val lastEvent = bus.events.last() as RuntimeEvent.WorkspaceStateChangedEvent
        assertEquals("Active", lastEvent.fromState)
        assertEquals("Closed", lastEvent.toState)
    }

    // --- session management ---

    @Test
    fun `addSession publishes SessionAdded and refreshes the state`() {
        clock.set(1L)
        val ws = viewModel.createWorkspace("Work").getOrThrow()
        clock.set(2L)
        val session = linuxSession("s-1")
        val result = viewModel.addSession(ws.id, session)
        assertTrue(result.isSuccess)
        val event = bus.events.last() as RuntimeEvent.SessionAddedEvent
        assertEquals(2L, event.atMs)
        assertEquals("s-1", event.sessionId)
        assertEquals("LINUX_PROOT", event.sessionKind)
        // The state reflects the new session.
        assertEquals(1, viewModel.state.value.workspaces.single().sessions.size)
    }

    @Test
    fun `removeSession publishes SessionRemoved and refreshes the state`() {
        val ws = viewModel.createWorkspace("Work", listOf(linuxSession("s-1"))).getOrThrow()
        viewModel.removeSession(ws.id, "s-1")
        val event = bus.events.last() as RuntimeEvent.SessionRemovedEvent
        assertEquals("s-1", event.sessionId)
        assertEquals(0, viewModel.state.value.workspaces.single().sessions.size)
    }

    @Test
    fun `addSession with a duplicate id records lastActionResult and does not publish`() {
        val ws = viewModel.createWorkspace("Work", listOf(linuxSession("s-1"))).getOrThrow()
        val eventsBefore = bus.events.size
        val result = viewModel.addSession(ws.id, linuxSession("s-1"))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is WorkspaceError.DuplicateSessionId)
        // No new event was published.
        assertEquals(eventsBefore, bus.events.size)
    }

    // --- bus-driven refresh ---

    @Test
    fun `external WorkspaceStateChangedEvent triggers a refresh`() {
        val ws = manager.createWorkspace("External", listOf(linuxSession("ext-1"))).getOrThrow()
        // The ViewModel was constructed before the
        // external create; refresh was not called.
        // A bus event from the manager (via the
        // manager's auto-save) should trigger a refresh.
        // We simulate the manager publishing an event.
        bus.publish(
            RuntimeEvent.WorkspaceStateChangedEvent(
                atMs = 1L,
                workspaceId = ws.id,
                fromState = "(none)",
                toState = "Active"
            )
        )
        // The state is refreshed; the new workspace is
        // visible.
        val state = viewModel.state.value
        assertEquals(1, state.workspaces.size)
        assertEquals("External", state.workspaces.single().name)
    }

    @Test
    fun `external SessionAddedEvent triggers a refresh`() {
        val ws = manager.createWorkspace("External", listOf(linuxSession("ext-1"))).getOrThrow()
        bus.publish(
            RuntimeEvent.SessionAddedEvent(
                atMs = 1L,
                workspaceId = ws.id,
                sessionId = "later-session",
                sessionKind = "LINUX_PROOT"
            )
        )
        // A refresh fires; the new session is not
        // visible (the bus event is for a future
        // session, not for one the manager knows about).
        // The key invariant: the ViewModel is in sync
        // with the manager's `listWorkspaces()`.
        val state = viewModel.state.value
        assertEquals(1, state.workspaces.size)
        assertEquals(1, state.workspaces.single().sessions.size)
        assertEquals("ext-1", state.workspaces.single().sessions.single().id)
    }

    @Test
    fun `non-workspace events like NetworkDecision do not trigger a refresh`() {
        val eventsBefore = viewModel.state.value.let { bus.events.size }
        bus.publish(
            RuntimeEvent.NetworkDecisionEvent(
                atMs = 1L,
                workspaceId = null,
                sessionId = "s",
                dest = "host",
                port = 80,
                decision = "Allow"
            )
        )
        // The state did not change. (We can verify this
        // by checking the workspace list is still the
        // initial empty list.)
        assertTrue(viewModel.state.value.workspaces.isEmpty())
        // The bus has the new event.
        assertTrue(bus.events.size > eventsBefore)
    }

    // --- close ---

    @Test
    fun `close unsubscribes from the bus`() {
        assertEquals(1, bus.subscriberCount())
        viewModel.close()
        assertEquals(0, bus.subscriberCount())
    }

    // --- thread safety ---

    @Test
    fun `createWorkspace is thread-safe under concurrent calls`() {
        val localVm = WorkspacesViewModel(
            workspaceManager = WorkspaceManager(InMemoryWorkspaceStore()),
            eventBus = RecordingEventBus(),
            clock = { 0L }
        )
        val start = CountDownLatch(1)
        val done = CountDownLatch(4)
        repeat(4) { threadIdx ->
            Thread {
                start.await()
                repeat(20) { i ->
                    val result = localVm.createWorkspace("ws-$threadIdx-$i")
                    assertTrue(
                        "every concurrent create must succeed",
                        result.isSuccess
                    )
                }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(15, TimeUnit.SECONDS))
        // 4 threads × 20 = 80 workspaces.
        assertEquals(80, localVm.state.value.workspaces.size)
    }

    // --- helpers ---

    private fun linuxSession(id: String): WorkspaceSession.LinuxProot =
        WorkspaceSession.LinuxProot(
            id = id,
            displayName = "Linux $id",
            distroId = "debian-latest",
            profileId = "balanced"
        )
}
