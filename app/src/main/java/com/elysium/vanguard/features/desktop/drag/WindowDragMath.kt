package com.elysium.vanguard.features.desktop.drag

import com.elysium.vanguard.features.desktop.model.WindowBounds

/**
 * Phase 78 — the pure math the
 * [com.elysium.vanguard.features.desktop.DesktopShellScreen]
 * uses to drag a window across the desktop.
 *
 * The drag math is split from the Compose
 * `pointerInput` modifier so the JVM test
 * suite can drive every (bounds, delta,
 * desktop bounds) combination without
 * instantiating the Android Compose
 * runtime. The modifier is the only thing
 * that converts pixel-space deltas to
 * [WindowBounds] (integer) coordinates; the
 * math is here.
 *
 * **Design rules**:
 * 1. A window must keep at least its title
 *    bar visible at all times (the user
 *    can always grab it to drag back).
 * 2. A window cannot be dragged past the
 *    bottom of the desktop (the dock is
 *    reserved space).
 * 3. A window can be dragged off the left
 *    / right edges (only the title bar
 *    must remain grabbable).
 * 4. The math operates on integer
 *    coordinates; the modifier rounds the
 *    float delta to int before calling.
 */
object WindowDragMath {

    /**
     * The minimum width of the window that
     * must remain on-screen at all times.
     * The constant is in pixels; the
     * modifier is responsible for passing
     * it in the right unit (it is
     * dimension-agnostic).
     */
    const val MIN_VISIBLE_WIDTH = 80

    /**
     * Apply a drag delta to a window's
     * bounds. The [deltaX] and [deltaY] are
     * the pixel deltas from the modifier's
     * `detectDragGestures` callback.
     *
     * The [titleBarHeight] is the height of
     * the window's title bar in pixels; the
     * clamp rules use it to ensure at least
     * the title bar is always grabbable.
     *
     * The [dockReservedHeight] is the
     * height (in pixels) of the dock at the
     * bottom of the desktop. The clamp
     * rules reserve this space so a window
     * cannot be dragged under the dock.
     *
     * Returns a new [WindowBounds] with
     * the dragged x / y; width and height
     * are unchanged. The function is
     * pure: it does not mutate the input
     * or read the ViewModel.
     */
    fun applyDrag(
        bounds: WindowBounds,
        deltaX: Float,
        deltaY: Float,
        desktopBounds: WindowBounds,
        titleBarHeight: Int,
        dockReservedHeight: Int,
    ): WindowBounds {
        val dx = deltaX.toInt()
        val dy = deltaY.toInt()
        // Compute the new x. The window can
        // be dragged left until at most
        // MIN_VISIBLE_WIDTH pixels remain on
        // the right edge of the desktop; it
        // can be dragged right until at most
        // MIN_VISIBLE_WIDTH pixels remain on
        // the left edge. This keeps the title
        // bar grabbable.
        val newX = (bounds.x + dx).coerceIn(
            minimumValue = desktopBounds.x - bounds.width + MIN_VISIBLE_WIDTH,
            maximumValue = desktopBounds.x + desktopBounds.width - MIN_VISIBLE_WIDTH,
        )
        // Compute the new y. The window can
        // be dragged up to the top of the
        // desktop (y = 0); it can be dragged
        // down until the title bar is just
        // above the dock. The window cannot
        // slide under the dock.
        val maxY = desktopBounds.y + desktopBounds.height -
            dockReservedHeight - titleBarHeight
        val newY = (bounds.y + dy).coerceIn(
            minimumValue = desktopBounds.y,
            maximumValue = maxY.coerceAtLeast(desktopBounds.y),
        )
        return bounds.copy(x = newX, y = newY)
    }

    /**
     * Compute the bounds of a window after
     * a resize. The math is simpler than
     * drag (no clamping to keep the window
     * grabbable; the resize is an explicit
     * user action). The function enforces:
     * - `width >= MIN_VISIBLE_WIDTH`
     * - `height >= titleBarHeight * 2` (a
     *   resized window must have a visible
     *   body in addition to the title bar)
     * - The window fits within the desktop
     *   bounds.
     *
     * The [proposed] bounds are the user's
     * requested new size; the function
     * returns a clamped variant.
     */
    fun clampResize(
        proposed: WindowBounds,
        desktopBounds: WindowBounds,
        titleBarHeight: Int,
        dockReservedHeight: Int,
    ): WindowBounds {
        val maxWidth = desktopBounds.width
        val maxHeight = desktopBounds.height - dockReservedHeight
        return WindowBounds(
            x = proposed.x.coerceIn(desktopBounds.x, desktopBounds.x + desktopBounds.width - MIN_VISIBLE_WIDTH),
            y = proposed.y.coerceIn(desktopBounds.y, desktopBounds.y + maxHeight - titleBarHeight),
            width = proposed.width.coerceIn(MIN_VISIBLE_WIDTH, maxWidth),
            height = proposed.height.coerceIn(titleBarHeight * 2, maxHeight),
        )
    }
}
