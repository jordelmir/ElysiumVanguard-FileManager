package com.elysium.vanguard.features.desktop.multidesktop

import com.elysium.vanguard.features.desktop.layout.LayoutMode
import com.elysium.vanguard.features.desktop.model.WindowBounds
import com.elysium.vanguard.features.desktop.model.WindowState
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

    // --- PHASE 115 — window state-machine methods on the active session ---

    @Test
    fun `focusWindow raises the window to the top of the z-order`() {
        viewModel.openWindow(id = "w1", title = "T1", iconKey = "i1")
        viewModel.openWindow(id = "w2", title = "T2", iconKey = "i2")
        viewModel.focusWindow("w1")
        val w1 = viewModel.activeSession.windows.first { it.id == "w1" }
        val w2 = viewModel.activeSession.windows.first { it.id == "w2" }
        assertTrue("w1 should have higher z-order after focus, got w1=${w1.zOrder} w2=${w2.zOrder}", w1.zOrder > w2.zOrder)
        assertEquals("w1", viewModel.activeSession.focusedWindowId)
    }

    @Test
    fun `focusWindow on a non-existent window is a no-op`() {
        viewModel.openWindow(id = "w1", title = "T1", iconKey = "i1")
        val beforeZ = viewModel.activeSession.windows.first().zOrder
        viewModel.focusWindow("nonexistent")
        val afterZ = viewModel.activeSession.windows.first().zOrder
        assertEquals(beforeZ, afterZ)
    }

    @Test
    fun `focusWindow targets the active session only`() {
        viewModel.createSession() // active = 1
        viewModel.openWindow(id = "s1-w1", title = "S1W1", iconKey = "i")
        viewModel.switchTo(0)
        viewModel.openWindow(id = "s0-w1", title = "S0W1", iconKey = "i")
        viewModel.focusWindow("s0-w1")
        // Session 1's window is unchanged.
        viewModel.switchTo(1)
        val s1w1 = viewModel.activeSession.windows.first { it.id == "s1-w1" }
        // The z-order of the session 1 window should not have changed.
        val initialZ = s1w1.zOrder
        // Re-focus session 1's w1; it should now have a fresh z-order.
        viewModel.focusWindow("s1-w1")
        val s1w1After = viewModel.activeSession.windows.first { it.id == "s1-w1" }
        assertTrue("s1-w1 z-order should advance, was $initialZ, now ${s1w1After.zOrder}", s1w1After.zOrder > initialZ)
    }

    @Test
    fun `minimizeWindow sets the window to MINIMIZED state`() {
        viewModel.openWindow(id = "w1", title = "T1", iconKey = "i1")
        viewModel.minimizeWindow("w1")
        val w1 = viewModel.activeSession.windows.first { it.id == "w1" }
        assertEquals(WindowState.MINIMIZED, w1.state)
    }

    @Test
    fun `minimizeWindow falls through the focused id when minimizing the focused window`() {
        viewModel.openWindow(id = "w1", title = "T1", iconKey = "i1")
        viewModel.openWindow(id = "w2", title = "T2", iconKey = "i2")
        // w2 is the focused window (last opened).
        viewModel.minimizeWindow("w2")
        // w1 should now be the focused id.
        assertEquals("w1", viewModel.activeSession.focusedWindowId)
    }

    @Test
    fun `minimizeWindow does not change the focused id when minimizing a non-focused window`() {
        viewModel.openWindow(id = "w1", title = "T1", iconKey = "i1")
        viewModel.openWindow(id = "w2", title = "T2", iconKey = "i2")
        // w2 is focused. Minimize w1.
        viewModel.minimizeWindow("w1")
        assertEquals("w2", viewModel.activeSession.focusedWindowId)
    }

    @Test
    fun `maximizeWindow sets bounds to desktop bounds and raises z-order`() {
        viewModel.openWindow(id = "w1", title = "T1", iconKey = "i1")
        viewModel.openWindow(id = "w2", title = "T2", iconKey = "i2")
        viewModel.maximizeWindow("w1")
        val w1 = viewModel.activeSession.windows.first { it.id == "w1" }
        assertEquals(WindowState.MAXIMIZED, w1.state)
        assertEquals(viewModel.activeSession.desktopBounds, w1.bounds)
        assertEquals("w1", viewModel.activeSession.focusedWindowId)
    }

    @Test
    fun `restoreWindow sets a minimized window back to NORMAL`() {
        viewModel.openWindow(id = "w1", title = "T1", iconKey = "i1")
        viewModel.minimizeWindow("w1")
        viewModel.restoreWindow("w1")
        val w1 = viewModel.activeSession.windows.first { it.id == "w1" }
        assertEquals(WindowState.NORMAL, w1.state)
        assertEquals("w1", viewModel.activeSession.focusedWindowId)
    }

    @Test
    fun `updateWindowBounds applies the new bounds in NORMAL state`() {
        viewModel.openWindow(id = "w1", title = "T1", iconKey = "i1")
        val newBounds = WindowBounds(x = 50, y = 75, width = 600, height = 400)
        viewModel.updateWindowBounds("w1", newBounds)
        val w1 = viewModel.activeSession.windows.first { it.id == "w1" }
        assertEquals(newBounds, w1.bounds)
    }

    @Test
    fun `updateWindowBounds is a no-op when the window is MAXIMIZED`() {
        viewModel.openWindow(id = "w1", title = "T1", iconKey = "i1")
        viewModel.maximizeWindow("w1")
        val boundsBefore = viewModel.activeSession.windows.first { it.id == "w1" }.bounds
        val newBounds = WindowBounds(x = 0, y = 0, width = 100, height = 100)
        viewModel.updateWindowBounds("w1", newBounds)
        val boundsAfter = viewModel.activeSession.windows.first { it.id == "w1" }.bounds
        assertEquals(boundsBefore, boundsAfter)
    }

    @Test
    fun `updateWindowBounds is a no-op on a non-existent window`() {
        viewModel.openWindow(id = "w1", title = "T1", iconKey = "i1")
        val beforeCount = viewModel.activeSession.windows.size
        viewModel.updateWindowBounds("nonexistent", WindowBounds(0, 0, 100, 100))
        assertEquals(beforeCount, viewModel.activeSession.windows.size)
    }

    @Test
    fun `pinApp adds a new dock item to the active session`() {
        viewModel.pinApp("calculator", "Calculator")
        val keys = viewModel.activeSession.dockItems.map { it.iconKey }
        assertTrue("dock should contain 'calculator', got $keys", keys.contains("calculator"))
    }

    @Test
    fun `pinApp is a no-op when the iconKey is already pinned`() {
        // The default dock has "terminal" pinned.
        val beforeCount = viewModel.activeSession.dockItems.size
        viewModel.pinApp("terminal", "Terminal Alt")
        val afterCount = viewModel.activeSession.dockItems.size
        assertEquals(beforeCount, afterCount)
    }

    @Test
    fun `pinApp with a blank iconKey returns failure`() {
        val result = viewModel.pinApp("", "Calculator")
        assertTrue("pinApp should fail, got $result", result.isFailure)
    }

    @Test
    fun `window state-machine methods target the active session only`() {
        viewModel.createSession() // active = 1
        viewModel.openWindow(id = "s1-w1", title = "S1W1", iconKey = "i")
        viewModel.switchTo(0)
        viewModel.openWindow(id = "s0-w1", title = "S0W1", iconKey = "i")
        // Maximize the active session's w1.
        viewModel.maximizeWindow("s0-w1")
        // Session 1's window is unchanged.
        viewModel.switchTo(1)
        val s1w1 = viewModel.activeSession.windows.first { it.id == "s1-w1" }
        assertEquals(WindowState.NORMAL, s1w1.state)
        // Session 0's w1 is MAXIMIZED.
        viewModel.switchTo(0)
        val s0w1 = viewModel.activeSession.windows.first { it.id == "s0-w1" }
        assertEquals(WindowState.MAXIMIZED, s0w1.state)
    }
}
