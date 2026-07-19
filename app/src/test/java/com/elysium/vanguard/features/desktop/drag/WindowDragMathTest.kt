package com.elysium.vanguard.features.desktop.drag

import com.elysium.vanguard.features.desktop.model.WindowBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 78 — the test suite for the
 * [WindowDragMath]. The math is pure
 * (no Android dependency) so every
 * clamp rule can be driven from the
 * JVM test suite.
 *
 * The desktop in the test scenarios is
 * a 1920x1080 rectangle with a 72px
 * dock reserved at the bottom. The
 * title bar is 36px tall. These match
 * the production `Dock` + `WindowFrame`
 * constants.
 */
class WindowDragMathTest {

    private val desktop = WindowBounds(0, 0, 1920, 1080)
    private val titleBarHeight = 36
    private val dockHeight = 72

    // --- applyDrag: basic translation ---

    @Test
    fun `drag right translates the window by the delta x`() {
        val bounds = WindowBounds(100, 100, 800, 600)
        val newBounds = WindowDragMath.applyDrag(
            bounds = bounds,
            deltaX = 50f,
            deltaY = 0f,
            desktopBounds = desktop,
            titleBarHeight = titleBarHeight,
            dockReservedHeight = dockHeight,
        )
        assertEquals(150, newBounds.x)
        assertEquals(100, newBounds.y)
        // width and height are unchanged
        assertEquals(800, newBounds.width)
        assertEquals(600, newBounds.height)
    }

    @Test
    fun `drag down translates the window by the delta y`() {
        val bounds = WindowBounds(100, 100, 800, 600)
        val newBounds = WindowDragMath.applyDrag(
            bounds = bounds,
            deltaX = 0f,
            deltaY = 80f,
            desktopBounds = desktop,
            titleBarHeight = titleBarHeight,
            dockReservedHeight = dockHeight,
        )
        assertEquals(100, newBounds.x)
        assertEquals(180, newBounds.y)
    }

    @Test
    fun `drag up clamps at the top edge`() {
        val bounds = WindowBounds(500, 10, 800, 600)
        val newBounds = WindowDragMath.applyDrag(
            bounds = bounds,
            deltaX = 0f,
            deltaY = -100f,
            desktopBounds = desktop,
            titleBarHeight = titleBarHeight,
            dockReservedHeight = dockHeight,
        )
        assertEquals("y must clamp to 0 (top of desktop)", 0, newBounds.y)
    }

    // --- applyDrag: clamp rules ---

    @Test
    fun `drag right clamps at the right edge keeping MIN_VISIBLE_WIDTH grabbable`() {
        val bounds = WindowBounds(1800, 100, 800, 600)
        // The desktop right edge is 1920. The
        // window can be at most desktop.x +
        // desktop.width - MIN_VISIBLE_WIDTH =
        // 1920 - 80 = 1840. So 1800 is OK; a
        // delta of 100 would push it past the
        // clamp.
        val newBounds = WindowDragMath.applyDrag(
            bounds = bounds,
            deltaX = 100f,
            deltaY = 0f,
            desktopBounds = desktop,
            titleBarHeight = titleBarHeight,
            dockReservedHeight = dockHeight,
        )
        assertEquals(
            "x must clamp to keep MIN_VISIBLE_WIDTH visible",
            desktop.x + desktop.width - WindowDragMath.MIN_VISIBLE_WIDTH,
            newBounds.x,
        )
    }

    @Test
    fun `drag left clamps at the left edge keeping MIN_VISIBLE_WIDTH grabbable`() {
        val bounds = WindowBounds(100, 100, 800, 600)
        // The window can be at most
        // desktop.x - bounds.width +
        // MIN_VISIBLE_WIDTH = 0 - 800 + 80
        // = -720. So 100 is fine; a delta
        // of -1000 would push it past the
        // clamp.
        val newBounds = WindowDragMath.applyDrag(
            bounds = bounds,
            deltaX = -1000f,
            deltaY = 0f,
            desktopBounds = desktop,
            titleBarHeight = titleBarHeight,
            dockReservedHeight = dockHeight,
        )
        assertEquals(
            "x must clamp to keep MIN_VISIBLE_WIDTH visible from the right",
            desktop.x - bounds.width + WindowDragMath.MIN_VISIBLE_WIDTH,
            newBounds.x,
        )
    }

    @Test
    fun `drag down clamps above the dock`() {
        val bounds = WindowBounds(500, 800, 800, 600)
        // The window's title bar must remain
        // above the dock. The maximum y is
        // desktop.y + desktop.height -
        // dockHeight - titleBarHeight =
        // 0 + 1080 - 72 - 36 = 972. So 800
        // is fine; a delta of 300 would push
        // it past the clamp.
        val newBounds = WindowDragMath.applyDrag(
            bounds = bounds,
            deltaX = 0f,
            deltaY = 300f,
            desktopBounds = desktop,
            titleBarHeight = titleBarHeight,
            dockReservedHeight = dockHeight,
        )
        assertEquals(
            "y must clamp above the dock",
            desktop.y + desktop.height - dockHeight - titleBarHeight,
            newBounds.y,
        )
    }

    @Test
    fun `drag returns the same x y when delta is zero`() {
        val bounds = WindowBounds(500, 300, 800, 600)
        val newBounds = WindowDragMath.applyDrag(
            bounds = bounds,
            deltaX = 0f,
            deltaY = 0f,
            desktopBounds = desktop,
            titleBarHeight = titleBarHeight,
            dockReservedHeight = dockHeight,
        )
        assertEquals(bounds, newBounds)
    }

    // --- applyDrag: float to int conversion ---

    @Test
    fun `drag rounds float deltas to int pixels (truncation toward zero)`() {
        val bounds = WindowBounds(100, 100, 800, 600)
        val newBounds = WindowDragMath.applyDrag(
            bounds = bounds,
            deltaX = 10.7f,
            deltaY = 5.9f,
            desktopBounds = desktop,
            titleBarHeight = titleBarHeight,
            dockReservedHeight = dockHeight,
        )
        // 10.7.toInt() == 10 (truncates); 5.9.toInt() == 5
        assertEquals(110, newBounds.x)
        assertEquals(105, newBounds.y)
    }

    // --- clampResize ---

    @Test
    fun `clampResize enforces minimum width`() {
        val proposed = WindowBounds(0, 0, 10, 100)
        val clamped = WindowDragMath.clampResize(
            proposed = proposed,
            desktopBounds = desktop,
            titleBarHeight = titleBarHeight,
            dockReservedHeight = dockHeight,
        )
        assertEquals(WindowDragMath.MIN_VISIBLE_WIDTH, clamped.width)
    }

    @Test
    fun `clampResize enforces minimum height (2 title bars)`() {
        val proposed = WindowBounds(0, 0, 800, 10)
        val clamped = WindowDragMath.clampResize(
            proposed = proposed,
            desktopBounds = desktop,
            titleBarHeight = titleBarHeight,
            dockReservedHeight = dockHeight,
        )
        assertEquals(titleBarHeight * 2, clamped.height)
    }

    @Test
    fun `clampResize enforces maximum width (desktop width)`() {
        val proposed = WindowBounds(0, 0, 5000, 600)
        val clamped = WindowDragMath.clampResize(
            proposed = proposed,
            desktopBounds = desktop,
            titleBarHeight = titleBarHeight,
            dockReservedHeight = dockHeight,
        )
        assertEquals(desktop.width, clamped.width)
    }

    @Test
    fun `clampResize enforces maximum height (desktop height minus dock)`() {
        val proposed = WindowBounds(0, 0, 800, 5000)
        val clamped = WindowDragMath.clampResize(
            proposed = proposed,
            desktopBounds = desktop,
            titleBarHeight = titleBarHeight,
            dockReservedHeight = dockHeight,
        )
        assertEquals(desktop.height - dockHeight, clamped.height)
    }

    @Test
    fun `clampResize passes a reasonable size through unchanged`() {
        val proposed = WindowBounds(100, 200, 800, 600)
        val clamped = WindowDragMath.clampResize(
            proposed = proposed,
            desktopBounds = desktop,
            titleBarHeight = titleBarHeight,
            dockReservedHeight = dockHeight,
        )
        assertEquals(proposed, clamped)
    }

    // --- edge case: very small desktop ---

    @Test
    fun `drag on a very small desktop does not produce negative width`() {
        val tinyDesktop = WindowBounds(0, 0, 200, 200)
        val bounds = WindowBounds(0, 0, 100, 100)
        val newBounds = WindowDragMath.applyDrag(
            bounds = bounds,
            deltaX = 0f,
            deltaY = 0f,
            desktopBounds = tinyDesktop,
            titleBarHeight = titleBarHeight,
            dockReservedHeight = dockHeight,
        )
        assertTrue("width must remain non-negative", newBounds.width >= 0)
        assertTrue("height must remain non-negative", newBounds.height >= 0)
    }
}
