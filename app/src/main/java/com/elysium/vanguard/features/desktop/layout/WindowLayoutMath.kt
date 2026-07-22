package com.elysium.vanguard.features.desktop.layout

import com.elysium.vanguard.features.desktop.model.DesktopWindow
import com.elysium.vanguard.features.desktop.model.WindowBounds
import com.elysium.vanguard.features.desktop.model.WindowState

/**
 * PHASE 112 — the **window layout math** for
 * the split-screen modes.
 *
 * The desktop shell supports two layout modes
 * (Phase 112):
 *
 *  - [LayoutMode.FREEFORM] — the user drags
 *    windows around the desktop. The bounds
 *    are whatever the user set (or the
 *    default centered bounds for a new
 *    window). The math is a no-op: the
 *    function returns the windows' existing
 *    bounds.
 *  - [LayoutMode.SPLIT_HORIZONTAL] — the
 *    visible windows are arranged in a
 *    single row, side by side. The first
 *    window is on the left, the second on
 *    the right, the third wraps below the
 *    first, etc. The math divides the
 *    desktop width by the row count + the
 *    height by the column count.
 *  - [LayoutMode.SPLIT_VERTICAL] — the
 *    visible windows are arranged in a
 *    single column, top to bottom. The
 *    first window is on top, the second
 *    below it, the third wraps to the right
 *    of the first, etc.
 *
 * **Why a pure function (not a Compose
 * modifier)**: the math is testable in
 * isolation (no Android dependencies). The
 * Compose layer reads the math's output
 * (the computed bounds) and applies them
 * to the window render.
 *
 * **Why the math is read-only (it does NOT
 * mutate the windows)**: the windows are
 * owned by the [com.elysium.vanguard.features.desktop.DesktopShellViewModel].
 * The math produces a "render override" map
 * the Compose layer applies on top of the
 * windows' stored bounds. The user can
 * switch back to [LayoutMode.FREEFORM] and
 * the windows' stored bounds are preserved.
 *
 * **Why minimized windows are excluded**:
 * the layout only arranges *visible*
 * windows. A minimized window is hidden
 * in the dock + the layout math skips it.
 */
object WindowLayoutMath {

    /**
     * The maximum number of windows the
     * split modes arrange in one row / column
     * before wrapping. The cap bounds the
     * per-window width / height: with
     * 1080-pixel-tall desktop + 4 rows of
     * 3 windows each, the per-window height
     * is 270 pixels (still usable).
     */
    const val MAX_PER_ROW: Int = 4
    const val MAX_PER_COLUMN: Int = 4

    /**
     * Compute the rendered bounds for every
     * visible window. The function returns a
     * map of `windowId -> computedBounds`.
     * Windows that are not in the map render
     * at their stored bounds (FREEFORM-style).
     *
     * The function is a **pure** function of
     * the windows + the desktop bounds + the
     * layout mode. The same inputs produce
     * the same outputs on every call.
     */
    fun computeRenderedBounds(
        windows: List<DesktopWindow>,
        desktopBounds: WindowBounds,
        layoutMode: LayoutMode,
    ): Map<String, WindowBounds> {
        val visible = windows.filter { it.state != WindowState.MINIMIZED }
        if (visible.isEmpty() || layoutMode == LayoutMode.FREEFORM) {
            return emptyMap()
        }
        val reservedDock = DOCK_RESERVED_HEIGHT_PX
        val availableHeight = (desktopBounds.height - reservedDock).coerceAtLeast(0)
        val availableWidth = desktopBounds.width.coerceAtLeast(0)
        return when (layoutMode) {
            LayoutMode.FREEFORM -> emptyMap()
            LayoutMode.SPLIT_HORIZONTAL -> tileGrid(
                visible = visible,
                availableWidth = availableWidth,
                availableHeight = availableHeight,
                columns = MAX_PER_ROW,
            )
            LayoutMode.SPLIT_VERTICAL -> tileGrid(
                visible = visible,
                availableWidth = availableWidth,
                availableHeight = availableHeight,
                columns = 1,
            )
        }
    }

    /**
     * Tile the visible windows in a grid with
     * the given [columns] per row. The grid
     * is left-to-right, top-to-bottom. Each
     * window gets the same width + height
     * (the cell size).
     *
     * A 1-column grid is the "vertical
     * stack" mode. A 4-column grid is the
     * "horizontal" mode. The function is the
     * building block for both split modes.
     */
    private fun tileGrid(
        visible: List<DesktopWindow>,
        availableWidth: Int,
        availableHeight: Int,
        columns: Int,
    ): Map<String, WindowBounds> {
        if (columns <= 0) return emptyMap()
        val cellWidth = availableWidth / columns
        // The cell height is the full
        // available height divided by the
        // number of rows needed for the
        // visible windows.
        val rowCount = ((visible.size + columns - 1) / columns)
            .coerceAtLeast(1)
        val cellHeight = availableHeight / rowCount
        val result = mutableMapOf<String, WindowBounds>()
        visible.forEachIndexed { index, window ->
            val col = index % columns
            val row = index / columns
            result[window.id] = WindowBounds(
                x = col * cellWidth,
                y = row * cellHeight,
                width = cellWidth,
                height = cellHeight,
            )
        }
        return result
    }

    /**
     * The pixels reserved for the dock at
     * the bottom of the desktop. The split
     * layout arranges windows above the
     * dock; the user can still see the
     * dock at all times.
     */
    const val DOCK_RESERVED_HEIGHT_PX: Int = 72
}

/**
 * The layout mode for the desktop shell.
 * The mode is **persistent** (a user
 * preference); switching modes is a
 * ViewModel action.
 */
enum class LayoutMode {
    /**
     * Free-form layout. Windows float on
     * the desktop at user-defined bounds.
     * The math is a no-op.
     */
    FREEFORM,

    /**
     * Split layout, side by side. The
     * visible windows are arranged in a
     * single row, with at most
     * [WindowLayoutMath.MAX_PER_ROW] per
     * row. The first window is on the
     * left, the second on the right, the
     * third wraps to a second row, etc.
     */
    SPLIT_HORIZONTAL,

    /**
     * Split layout, stacked. The visible
     * windows are arranged in a single
     * column, one above the other.
     */
    SPLIT_VERTICAL,
}
