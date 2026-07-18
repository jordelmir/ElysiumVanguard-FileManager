package com.elysium.vanguard.features.desktop

import com.elysium.vanguard.features.desktop.model.DesktopSessionState
import com.elysium.vanguard.features.desktop.model.DockItemKind
import com.elysium.vanguard.features.desktop.model.WindowBounds
import com.elysium.vanguard.features.desktop.model.WindowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopShellViewModelTest {

    private val initialState = DesktopSessionState(
        windows = emptyList(),
        focusedWindowId = null,
        dockItems = emptyList(),
        desktopBounds = WindowBounds(0, 0, 1920, 1080),
    )
    private val viewModel = DesktopShellViewModel(initialState)

    @Test
    fun `initial state is empty`() {
        assertTrue(viewModel.state.value.windows.isEmpty())
        assertNull(viewModel.state.value.focusedWindowId)
        assertTrue(viewModel.state.value.dockItems.isEmpty())
    }

    @Test
    fun `openWindow adds a window and focuses it`() {
        viewModel.openWindow(id = "app-1", title = "Terminal", iconKey = "terminal")
        val state = viewModel.state.value
        assertEquals(1, state.windows.size)
        assertEquals("app-1", state.windows.first().id)
        assertEquals(WindowState.NORMAL, state.windows.first().state)
        assertEquals("app-1", state.focusedWindowId)
    }

    @Test
    fun `openWindow adds a RUNNING_WINDOW dock item`() {
        viewModel.openWindow(id = "app-1", title = "Terminal", iconKey = "terminal")
        val dockItem = viewModel.state.value.dockItems.first()
        assertEquals(DockItemKind.RUNNING_WINDOW, dockItem.kind)
        assertEquals("app-1", dockItem.windowId)
    }

    @Test
    fun `openWindow is a no-op when the window is already open`() {
        viewModel.openWindow(id = "app-1", title = "Terminal", iconKey = "terminal")
        val firstZ = viewModel.state.value.windows.first().zOrder
        viewModel.openWindow(id = "app-1", title = "Terminal", iconKey = "terminal")
        assertEquals(1, viewModel.state.value.windows.size)
        // zOrder should not change (no-op).
        assertEquals(firstZ, viewModel.state.value.windows.first().zOrder)
    }

    @Test
    fun `openWindow rejects blank id`() {
        val result = viewModel.openWindow(id = "", title = "T", iconKey = "i")
        assertTrue(result.isFailure)
    }

    @Test
    fun `closeWindow removes the window and the dock item`() {
        viewModel.openWindow(id = "app-1", title = "Terminal", iconKey = "terminal")
        viewModel.openWindow(id = "app-2", title = "Files", iconKey = "files")
        viewModel.closeWindow("app-1")
        val state = viewModel.state.value
        assertEquals(1, state.windows.size)
        assertEquals("app-2", state.windows.first().id)
        assertEquals(1, state.dockItems.size)
        assertEquals("app-2", state.dockItems.first().windowId)
    }

    @Test
    fun `closeWindow focuses the next top-most window when the focused window is closed`() {
        viewModel.openWindow(id = "app-1", title = "T1", iconKey = "i1")
        viewModel.openWindow(id = "app-2", title = "T2", iconKey = "i2")
        // app-2 is now focused (most recent).
        assertEquals("app-2", viewModel.state.value.focusedWindowId)
        viewModel.closeWindow("app-2")
        // app-1 should be the new focus.
        assertEquals("app-1", viewModel.state.value.focusedWindowId)
    }

    @Test
    fun `closeWindow is a no-op when the window is not open`() {
        viewModel.openWindow(id = "app-1", title = "T", iconKey = "i")
        val before = viewModel.state.value
        viewModel.closeWindow("does-not-exist")
        assertEquals(before, viewModel.state.value)
    }

    @Test
    fun `focusWindow brings the window to the top`() {
        viewModel.openWindow(id = "app-1", title = "T1", iconKey = "i1")
        viewModel.openWindow(id = "app-2", title = "T2", iconKey = "i2")
        val before = viewModel.state.value
        viewModel.focusWindow("app-1")
        val after = viewModel.state.value
        // app-1's zOrder should be higher than app-2's.
        val app1 = after.windows.first { it.id == "app-1" }
        val app2 = after.windows.first { it.id == "app-2" }
        assertTrue(app1.zOrder > app2.zOrder)
        assertEquals("app-1", after.focusedWindowId)
    }

    @Test
    fun `focusWindow restores a minimized window to NORMAL`() {
        viewModel.openWindow(id = "app-1", title = "T", iconKey = "i")
        viewModel.minimizeWindow("app-1")
        assertEquals(WindowState.MINIMIZED, viewModel.state.value.windows.first().state)
        viewModel.focusWindow("app-1")
        assertEquals(WindowState.NORMAL, viewModel.state.value.windows.first().state)
    }

    @Test
    fun `minimizeWindow sets the state to MINIMIZED`() {
        viewModel.openWindow(id = "app-1", title = "T", iconKey = "i")
        viewModel.minimizeWindow("app-1")
        assertEquals(WindowState.MINIMIZED, viewModel.state.value.windows.first().state)
    }

    @Test
    fun `minimizeWindow removes the window from the focus if it was focused`() {
        viewModel.openWindow(id = "app-1", title = "T1", iconKey = "i1")
        viewModel.openWindow(id = "app-2", title = "T2", iconKey = "i2")
        viewModel.minimizeWindow("app-2")
        assertEquals("app-1", viewModel.state.value.focusedWindowId)
    }

    @Test
    fun `maximizeWindow sets the state to MAXIMIZED and the bounds to the desktop bounds`() {
        viewModel.openWindow(id = "app-1", title = "T", iconKey = "i")
        viewModel.maximizeWindow("app-1")
        val window = viewModel.state.value.windows.first()
        assertEquals(WindowState.MAXIMIZED, window.state)
        assertEquals(viewModel.state.value.desktopBounds, window.bounds)
    }

    @Test
    fun `restoreWindow sets the state to NORMAL`() {
        viewModel.openWindow(id = "app-1", title = "T", iconKey = "i")
        viewModel.maximizeWindow("app-1")
        viewModel.restoreWindow("app-1")
        assertEquals(WindowState.NORMAL, viewModel.state.value.windows.first().state)
    }

    @Test
    fun `pinApp adds a PINNED_APP dock item`() {
        viewModel.pinApp(iconKey = "browser", label = "Browser")
        val item = viewModel.state.value.dockItems.first()
        assertEquals(DockItemKind.PINNED_APP, item.kind)
        assertNull(item.windowId)
    }

    @Test
    fun `pinApp is a no-op when the app is already pinned`() {
        viewModel.pinApp(iconKey = "browser", label = "Browser")
        val before = viewModel.state.value.dockItems.size
        viewModel.pinApp(iconKey = "browser", label = "Browser")
        assertEquals(before, viewModel.state.value.dockItems.size)
    }

    @Test
    fun `pinApp rejects blank iconKey or label`() {
        assertTrue(viewModel.pinApp(iconKey = "", label = "X").isFailure)
        assertTrue(viewModel.pinApp(iconKey = "x", label = "").isFailure)
    }

    @Test
    fun `zOrder is monotonically increasing across focus events`() {
        viewModel.openWindow(id = "app-1", title = "T", iconKey = "i")
        val firstZ = viewModel.state.value.windows.first().zOrder
        viewModel.focusWindow("app-1")
        val secondZ = viewModel.state.value.windows.first().zOrder
        assertTrue("zOrder must increase on focus", secondZ > firstZ)
    }

    @Test
    fun `multiple windows have unique zOrders`() {
        viewModel.openWindow(id = "app-1", title = "T1", iconKey = "i1")
        viewModel.openWindow(id = "app-2", title = "T2", iconKey = "i2")
        viewModel.openWindow(id = "app-3", title = "T3", iconKey = "i3")
        val zOrders = viewModel.state.value.windows.map { it.zOrder }
        assertEquals(zOrders.size, zOrders.toSet().size)
    }
}
