package com.elysium.vanguard.features.dualpane

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PaneTabsTest {

    private val root = File("/root")
    private val a = File("/a")
    private val b = File("/b")
    private val c = File("/c")

    @Test
    fun `initial has one tab at index 0`() {
        val state = PaneTabs.initial(root)
        assertEquals(1, state.count)
        assertEquals(0, state.activeIndex)
        assertEquals(root, state.active.dir)
    }

    @Test
    fun `openTab appends and activates the new tab with unique ids`() {
        var state = PaneTabs.initial(root)
        state = PaneTabs.openTab(state, a)
        assertEquals(2, state.count)
        assertEquals(1, state.activeIndex)
        assertEquals(a, state.active.dir)

        state = PaneTabs.openTab(state, b)
        assertEquals(3, state.count)
        assertEquals(2, state.activeIndex)
        assertEquals(3, state.tabs.map { it.id }.distinct().size)
    }

    @Test
    fun `closeTab of a tab before the active one shifts the active index`() {
        var state = PaneTabs.initial(root)
        state = PaneTabs.openTab(state, a)
        state = PaneTabs.openTab(state, b)     // active = 2
        state = PaneTabs.closeTab(state, 0)!!  // close root
        assertEquals(2, state.count)
        assertEquals(1, state.activeIndex)
        assertEquals(b, state.active.dir)
    }

    @Test
    fun `closeTab of the active tab falls back to the neighbour`() {
        var state = PaneTabs.initial(root)
        state = PaneTabs.openTab(state, a)     // active = 1 (a)
        state = PaneTabs.closeTab(state, 1)!!  // close active a
        assertEquals(1, state.count)
        assertEquals(0, state.activeIndex)
        assertEquals(root, state.active.dir)
    }

    @Test
    fun `closeTab of a tab after the active one keeps the active index`() {
        var state = PaneTabs.initial(root)
        state = PaneTabs.openTab(state, a)     // active = 1
        state = PaneTabs.openTab(state, b)     // active = 2
        state = PaneTabs.switchTo(state, 1)    // back to a
        state = PaneTabs.closeTab(state, 2)!!  // close b
        assertEquals(1, state.activeIndex)
        assertEquals(a, state.active.dir)
    }

    @Test
    fun `closeTab refuses to close the last remaining tab`() {
        val state = PaneTabs.initial(root)
        assertNull(PaneTabs.closeTab(state, 0))
    }

    @Test
    fun `closeTab with out-of-range index returns state unchanged`() {
        val state = PaneTabs.initial(root)
        assertEquals(state, PaneTabs.closeTab(state, 7))
    }

    @Test
    fun `switchTo activates the tab and ignores bad indexes`() {
        var state = PaneTabs.initial(root)
        state = PaneTabs.openTab(state, a)
        state = PaneTabs.switchTo(state, 0)
        assertEquals(0, state.activeIndex)
        assertEquals(root, state.active.dir)
        assertEquals(state, PaneTabs.switchTo(state, 99))
    }

    @Test
    fun `updateActiveDir only changes the active tab`() {
        var state = PaneTabs.initial(root)
        state = PaneTabs.openTab(state, a)
        state = PaneTabs.switchTo(state, 0)
        state = PaneTabs.updateActiveDir(state, c)
        assertEquals(c, state.tabs[0].dir)
        assertEquals(a, state.tabs[1].dir)
        assertEquals(0, state.activeIndex)

        // Same dir is a no-op (identity).
        assertEquals(state, PaneTabs.updateActiveDir(state, c))
    }

    @Test
    fun `ids stay unique across open and close cycles`() {
        var state = PaneTabs.initial(root)
        state = PaneTabs.openTab(state, a)
        state = PaneTabs.closeTab(state, 0)!!
        state = PaneTabs.openTab(state, b)
        state = PaneTabs.openTab(state, c)
        assertEquals(state.tabs.size, state.tabs.map { it.id }.distinct().size)
        assertTrue(state.tabs.maxOf { it.id } >= 4)
    }
}
