package com.elysium.vanguard.features.desktop.layout

import com.elysium.vanguard.features.desktop.model.DesktopWindow
import com.elysium.vanguard.features.desktop.model.WindowBounds
import com.elysium.vanguard.features.desktop.model.WindowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 112 — the test suite for the
 * [WindowLayoutMath]. The math is a pure
 * function (no Android dependencies); the
 * tests are pure JVM.
 */
class WindowLayoutMathTest {

    private val desktopBounds = WindowBounds(
        x = 0, y = 0,
        width = 1920, height = 1080,
    )

    // --- FREEFORM mode ---

    @Test
    fun `FREEFORM mode returns an empty override map`() {
        val windows = listOf(
            sampleWindow("w1"),
            sampleWindow("w2"),
        )
        val result = WindowLayoutMath.computeRenderedBounds(
            windows = windows,
            desktopBounds = desktopBounds,
            layoutMode = LayoutMode.FREEFORM,
        )
        assertTrue(
            "FREEFORM should produce no overrides, got $result",
            result.isEmpty()
        )
    }

    @Test
    fun `FREEFORM mode with no windows returns an empty map`() {
        val result = WindowLayoutMath.computeRenderedBounds(
            windows = emptyList(),
            desktopBounds = desktopBounds,
            layoutMode = LayoutMode.FREEFORM,
        )
        assertTrue(result.isEmpty())
    }

    // --- SPLIT_HORIZONTAL ---

    @Test
    fun `SPLIT_HORIZONTAL with 2 windows divides the width in half`() {
        val w1 = sampleWindow("w1")
        val w2 = sampleWindow("w2")
        val result = WindowLayoutMath.computeRenderedBounds(
            windows = listOf(w1, w2),
            desktopBounds = desktopBounds,
            layoutMode = LayoutMode.SPLIT_HORIZONTAL,
        )
        // 2 windows, columns=MAX_PER_ROW=4,
        // so row count = 1, cell width =
        // 1920/4 = 480, cell height = 1080/1
        // = 1080 (less the dock reserved
        // height).
        val w1Bounds = result.getValue("w1")
        val w2Bounds = result.getValue("w2")
        assertEquals(0, w1Bounds.x)
        assertEquals(480, w1Bounds.width)
        assertEquals(480, w2Bounds.x)
        assertEquals(480, w2Bounds.width)
        // Heights match (single row).
        assertEquals(w1Bounds.height, w2Bounds.height)
    }

    @Test
    fun `SPLIT_HORIZONTAL wraps to a second row when more than MAX_PER_ROW windows`() {
        // 5 windows, columns = 4 → row 1
        // has 4 windows, row 2 has 1 window.
        val windows = (1..5).map { sampleWindow("w$it") }
        val result = WindowLayoutMath.computeRenderedBounds(
            windows = windows,
            desktopBounds = desktopBounds,
            layoutMode = LayoutMode.SPLIT_HORIZONTAL,
        )
        // 4 windows fit in row 1 (cols 0..3).
        // w5 is in row 2, col 0.
        val w5Bounds = result.getValue("w5")
        assertEquals(0, w5Bounds.x)
        // Row 2 starts at y = cellHeight (the
        // first row's height). With 2 rows,
        // cell height = (1080 - 72) / 2 = 504.
        assertEquals(504, w5Bounds.y)
    }

    // --- SPLIT_VERTICAL ---

    @Test
    fun `SPLIT_VERTICAL with 2 windows divides the height in half`() {
        val w1 = sampleWindow("w1")
        val w2 = sampleWindow("w2")
        val result = WindowLayoutMath.computeRenderedBounds(
            windows = listOf(w1, w2),
            desktopBounds = desktopBounds,
            layoutMode = LayoutMode.SPLIT_VERTICAL,
        )
        val w1Bounds = result.getValue("w1")
        val w2Bounds = result.getValue("w2")
        // Single column. w1 at the top, w2
        // below it.
        assertEquals(0, w1Bounds.x)
        assertEquals(0, w1Bounds.y)
        assertEquals(desktopBounds.width, w1Bounds.width)
        // w2 is at y = cellHeight, where
        // cellHeight = (1080 - 72) / 2 = 504.
        assertEquals(504, w2Bounds.y)
    }

    @Test
    fun `SPLIT_VERTICAL with 3 windows stacks them top to bottom`() {
        val windows = (1..3).map { sampleWindow("w$it") }
        val result = WindowLayoutMath.computeRenderedBounds(
            windows = windows,
            desktopBounds = desktopBounds,
            layoutMode = LayoutMode.SPLIT_VERTICAL,
        )
        // 3 windows, single column, 3 rows.
        // cellHeight = (1080 - 72) / 3 = 336.
        val w1Bounds = result.getValue("w1")
        val w2Bounds = result.getValue("w2")
        val w3Bounds = result.getValue("w3")
        assertEquals(0, w1Bounds.y)
        assertEquals(336, w2Bounds.y)
        assertEquals(672, w3Bounds.y)
    }

    // --- Minimize interaction ---

    @Test
    fun `minimized windows are excluded from the layout math`() {
        val w1 = sampleWindow("w1")
        val w2 = sampleWindow("w2", state = WindowState.MINIMIZED)
        val w3 = sampleWindow("w3")
        val result = WindowLayoutMath.computeRenderedBounds(
            windows = listOf(w1, w2, w3),
            desktopBounds = desktopBounds,
            layoutMode = LayoutMode.SPLIT_HORIZONTAL,
        )
        // Only w1 + w3 are visible. w2 is
        // not in the map.
        assertTrue("w1 should be in the result", result.containsKey("w1"))
        assertTrue("w3 should be in the result", result.containsKey("w3"))
        assertTrue(
            "minimized w2 should NOT be in the result, got $result",
            !result.containsKey("w2")
        )
    }

    // --- Dock reservation ---

    @Test
    fun `layout math reserves the dock height at the bottom`() {
        val windows = listOf(sampleWindow("w1"))
        val result = WindowLayoutMath.computeRenderedBounds(
            windows = windows,
            desktopBounds = desktopBounds,
            layoutMode = LayoutMode.SPLIT_HORIZONTAL,
        )
        val bounds = result.getValue("w1")
        // The cell height = (1080 - 72) / 1
        // = 1008 (the dock's 72 pixels are
        // reserved).
        assertEquals(1008, bounds.height)
    }

    // --- Edge cases ---

    @Test
    fun `layout math with no visible windows returns an empty map`() {
        val windows = listOf(
            sampleWindow("w1", state = WindowState.MINIMIZED),
            sampleWindow("w2", state = WindowState.MINIMIZED),
        )
        val result = WindowLayoutMath.computeRenderedBounds(
            windows = windows,
            desktopBounds = desktopBounds,
            layoutMode = LayoutMode.SPLIT_HORIZONTAL,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `layout math with a single window produces a full-width tile`() {
        val windows = listOf(sampleWindow("w1"))
        val result = WindowLayoutMath.computeRenderedBounds(
            windows = windows,
            desktopBounds = desktopBounds,
            layoutMode = LayoutMode.SPLIT_HORIZONTAL,
        )
        val bounds = result.getValue("w1")
        // Single window in a 4-column grid
        // takes 1/4 of the width.
        assertEquals(0, bounds.x)
        assertEquals(0, bounds.y)
        assertEquals(480, bounds.width)
        assertEquals(1008, bounds.height)
    }

    // --- helper ---

    private fun sampleWindow(
        id: String,
        state: WindowState = WindowState.NORMAL,
    ): DesktopWindow = DesktopWindow(
        id = id,
        title = "Test Window $id",
        iconKey = "test",
        state = state,
        bounds = WindowBounds(x = 0, y = 0, width = 800, height = 600),
        zOrder = 1,
        lastInteractionAt = 0L,
    )
}
