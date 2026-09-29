package com.elysium.vanguard.features.dualpane

import java.io.File

/**
 * Tab model for one pane of the dual-pane browser (Phase 2.10 tabs).
 *
 * Pure state machine — no Android, no I/O — so the open/close/switch
 * index arithmetic is unit-testable on the JVM. Each tab remembers its
 * own directory; only the active tab's directory is mirrored into the
 * pane's [PaneState].
 */
data class PaneTab(
    val id: Long,
    val dir: File,
)

data class PaneTabsState(
    val tabs: List<PaneTab>,
    val activeIndex: Int = 0,
) {
    val active: PaneTab get() = tabs[activeIndex]
    val count: Int get() = tabs.size
}

object PaneTabs {

    fun initial(dir: File): PaneTabsState =
        PaneTabsState(tabs = listOf(PaneTab(id = 1L, dir = dir)), activeIndex = 0)

    /** Append a tab pointing at [dir] and make it active. */
    fun openTab(state: PaneTabsState, dir: File): PaneTabsState {
        val nextId = (state.tabs.maxOf { it.id }) + 1
        return PaneTabsState(
            tabs = state.tabs + PaneTab(id = nextId, dir = dir),
            activeIndex = state.tabs.size,
        )
    }

    /**
     * Close tab [index]. Returns `null` when the last remaining tab would
     * be closed (a pane always keeps at least one tab) or [index] is out
     * of range the state is returned unchanged for the out-of-range case.
     */
    fun closeTab(state: PaneTabsState, index: Int): PaneTabsState? {
        if (index !in state.tabs.indices) return state
        if (state.tabs.size <= 1) return null
        val newTabs = state.tabs.filterIndexed { i, _ -> i != index }
        val newActive = when {
            index < state.activeIndex -> state.activeIndex - 1
            index > state.activeIndex -> state.activeIndex
            else -> minOf(state.activeIndex, newTabs.size - 1)
        }
        return PaneTabsState(tabs = newTabs, activeIndex = newActive)
    }

    /** Activate tab [index]; out-of-range indexes are ignored. */
    fun switchTo(state: PaneTabsState, index: Int): PaneTabsState =
        if (index in state.tabs.indices) state.copy(activeIndex = index) else state

    /** Point the active tab at [dir] (folder navigation). */
    fun updateActiveDir(state: PaneTabsState, dir: File): PaneTabsState {
        if (state.active.dir == dir) return state
        val tabs = state.tabs.toMutableList()
        tabs[state.activeIndex] = tabs[state.activeIndex].copy(dir = dir)
        return state.copy(tabs = tabs)
    }
}
