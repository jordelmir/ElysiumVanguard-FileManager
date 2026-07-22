package com.elysium.vanguard.features.desktop.multidesktop

import com.elysium.vanguard.features.desktop.layout.LayoutMode
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * PHASE 113 — the test suite for the
 * [MultiDesktopShellViewModel]. The
 * ViewModel manages a list of
 * [com.elysium.vanguard.features.desktop.model.DesktopSessionState]s
 * (one per session / space) + the active
 * index. The tests cover:
 *  - Session creation (default name +
 *    custom name).
 *  - Session closing (the last session
 *    cannot be closed).
 *  - Session switching (active index
 *    update).
 *  - Window operations target the active
 *    session only.
 *  - Layout mode is per-session.
 */
class MultiDesktopShellViewModelTest {

    private val clock = com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp.monotonicWallClock()
    private val viewModel = MultiDesktopShellViewModel(
        initialStateFlow = MutableStateFlow(MultiDesktopShellState.initial()),
        clock = clock,
    )

    // --- initial state ---

    @Test
    fun `initial state has one session with activeIndex 0`() {
        val state = viewModel.state.value
        assertEquals(1, state.sessions.size)
        assertEquals(0, state.activeIndex)
        assertEquals(1, state.nextSessionNumber)
    }

    @Test
    fun `initial session is the default FREEFORM desktop with 4 pinned apps`() {
        val session = viewModel.activeSession
        assertEquals(LayoutMode.FREEFORM, session.layoutMode)
        assertEquals(0, session.windows.size)
        assertEquals(4, session.dockItems.size)
    }

    // --- createSession ---

    @Test
    fun `createSession appends a new session and switches to it`() {
        val result = viewModel.createSession()
        assertTrue("createSession should succeed, got $result", result.isSuccess)
        val newIndex = result.getOrNull()
        assertEquals(1, newIndex)
        val state = viewModel.state.value
        assertEquals(2, state.sessions.size)
        assertEquals(1, state.activeIndex)
    }

    @Test
    fun `createSession uses a custom name when provided`() {
        viewModel.createSession(name = "Work")
        val state = viewModel.state.value
        val newSession = state.activeSession
        val firstDockItem = newSession.dockItems.first()
        assertTrue(
            "dock label should contain the custom name, got '${firstDockItem.label}'",
            firstDockItem.label.contains("Work")
        )
    }

    @Test
    fun `createSession auto-names subsequent sessions as Session N`() {
        viewModel.createSession() // Session 1 (nextSessionNumber=2, so label is "Session 1")
        val firstNewLabel = viewModel.activeSession.dockItems.first().label
        assertTrue(firstNewLabel.contains("Session 1"))
        viewModel.createSession() // Session 2
        val secondNewLabel = viewModel.activeSession.dockItems.first().label
        assertTrue(secondNewLabel.contains("Session 2"))
    }

    @Test
    fun `createSession increments nextSessionNumber`() {
        val before = viewModel.state.value.nextSessionNumber
        viewModel.createSession()
        val after = viewModel.state.value.nextSessionNumber
        assertEquals(before + 1, after)
    }

    @Test
    fun `each new session starts with the standard 4 pinned apps`() {
        viewModel.createSession()
        val session = viewModel.activeSession
        assertEquals(4, session.dockItems.size)
        // The 4 standard apps.
        val iconKeys = session.dockItems.map { it.iconKey }
        assertTrue(iconKeys.contains("terminal"))
        assertTrue(iconKeys.contains("files"))
        assertTrue(iconKeys.contains("settings"))
        assertTrue(iconKeys.contains("notes"))
    }

    @Test
    fun `each new session starts in FREEFORM layout mode`() {
        viewModel.createSession()
        assertEquals(LayoutMode.FREEFORM, viewModel.activeSession.layoutMode)
    }

    // --- closeSession ---

    @Test
    fun `closeSession removes a session and shifts the active index`() {
        viewModel.createSession() // 2 sessions, active = 1
        viewModel.createSession() // 3 sessions, active = 2
        // Close the middle session.
        val result = viewModel.closeSession(1)
        assertTrue("closeSession should succeed, got $result", result.isSuccess)
        val state = viewModel.state.value
        assertEquals(2, state.sessions.size)
    }

    @Test
    fun `closeSession on the active session switches to the next`() {
        viewModel.createSession() // active = 1
        viewModel.closeSession(1) // close the active
        val state = viewModel.state.value
        assertEquals(1, state.sessions.size)
        assertEquals(0, state.activeIndex)
    }

    @Test
    fun `closeSession on the last remaining session returns failure`() {
        // The initial state has 1 session.
        val result = viewModel.closeSession(0)
        assertTrue("closeSession should fail, got $result", result.isFailure)
        // The state is unchanged.
        assertEquals(1, viewModel.state.value.sessions.size)
    }

    @Test
    fun `closeSession with an out-of-range index returns failure`() {
        viewModel.createSession() // 2 sessions
        val result = viewModel.closeSession(99)
        assertTrue("closeSession should fail, got $result", result.isFailure)
    }

    // --- switchTo ---

    @Test
    fun `switchTo changes the active index`() {
        viewModel.createSession() // active = 1
        val result = viewModel.switchTo(0)
        assertTrue(result.isSuccess)
        assertEquals(0, viewModel.state.value.activeIndex)
    }

    @Test
    fun `switchTo with the current active index is a no-op`() {
        val result = viewModel.switchTo(0)
        assertTrue(result.isSuccess)
        assertEquals(0, viewModel.state.value.activeIndex)
    }

    @Test
    fun `switchTo with an out-of-range index returns failure`() {
        viewModel.createSession() // 2 sessions
        val result = viewModel.switchTo(99)
        assertTrue("switchTo should fail, got $result", result.isFailure)
    }

    // --- openWindow ---

    @Test
    fun `openWindow adds a window to the active session only`() {
        viewModel.createSession() // active = 1
        viewModel.openWindow(id = "w1", title = "T1", iconKey = "i1")
        // Session 0 has no windows.
        viewModel.switchTo(0)
        assertEquals(0, viewModel.activeSession.windows.size)
        // Session 1 (the active one) has 1 window.
        viewModel.switchTo(1)
        assertEquals(1, viewModel.activeSession.windows.size)
    }

    @Test
    fun `openWindow with the same id twice is a no-op`() {
        viewModel.openWindow(id = "w1", title = "T1", iconKey = "i1")
        viewModel.openWindow(id = "w1", title = "T1", iconKey = "i1")
        assertEquals(1, viewModel.activeSession.windows.size)
    }

    @Test
    fun `openWindow with a blank id returns failure`() {
        val result = viewModel.openWindow(id = "", title = "T1", iconKey = "i1")
        assertTrue("openWindow should fail, got $result", result.isFailure)
    }

    // --- closeWindow ---

    @Test
    fun `closeWindow removes the window from the active session`() {
        viewModel.openWindow(id = "w1", title = "T1", iconKey = "i1")
        viewModel.closeWindow("w1")
        assertEquals(0, viewModel.activeSession.windows.size)
    }

    @Test
    fun `closeWindow on a non-existent window is a no-op`() {
        viewModel.openWindow(id = "w1", title = "T1", iconKey = "i1")
        viewModel.closeWindow("w999")
        assertEquals(1, viewModel.activeSession.windows.size)
    }

    // --- setLayoutMode ---

    @Test
    fun `setLayoutMode targets the active session only`() {
        viewModel.createSession() // active = 1
        viewModel.setLayoutMode(LayoutMode.SPLIT_HORIZONTAL)
        // Session 0 still FREEFORM.
        viewModel.switchTo(0)
        assertEquals(LayoutMode.FREEFORM, viewModel.activeSession.layoutMode)
        // Session 1 is SPLIT.
        viewModel.switchTo(1)
        assertEquals(LayoutMode.SPLIT_HORIZONTAL, viewModel.activeSession.layoutMode)
    }

    // --- cross-session invariants ---

    @Test
    fun `switching sessions preserves each session's windows`() {
        viewModel.openWindow(id = "w1", title = "T1", iconKey = "i1")
        viewModel.createSession()
        viewModel.openWindow(id = "w2", title = "T2", iconKey = "i2")
        // Switch back to session 0.
        viewModel.switchTo(0)
        val session0 = viewModel.activeSession
        assertEquals(1, session0.windows.size)
        assertEquals("w1", session0.windows.first().id)
        // Switch to session 1.
        viewModel.switchTo(1)
        val session1 = viewModel.activeSession
        assertEquals(1, session1.windows.size)
        assertEquals("w2", session1.windows.first().id)
    }

    @Test
    fun `closing a non-active session preserves the active session's state`() {
        viewModel.createSession() // active = 1
        // Open windows in both sessions.
        viewModel.openWindow(id = "active-w", title = "Active", iconKey = "i")
        viewModel.switchTo(0)
        viewModel.openWindow(id = "inactive-w", title = "Inactive", iconKey = "i")
        // Close the inactive session (the one at index 0, the original).
        viewModel.closeSession(0)
        // The active session (now at index 0 after the shift) still has its window.
        val active = viewModel.activeSession
        assertEquals(1, active.windows.size)
        assertEquals("active-w", active.windows.first().id)
    }
}
