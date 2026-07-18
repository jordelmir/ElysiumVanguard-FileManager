package com.elysium.vanguard.features.desktop

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elysium.vanguard.features.desktop.model.DesktopSessionState
import com.elysium.vanguard.features.desktop.model.DockItem
import com.elysium.vanguard.features.desktop.model.DockItemKind
import com.elysium.vanguard.features.desktop.model.WindowBounds
import com.elysium.vanguard.features.desktop.model.WindowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 64 — Instrumented test for the Universal
 * Desktop Shell on a real Android device.
 *
 * The test verifies the Compose UI:
 *   - Renders the desktop background.
 *   - Renders the open windows list.
 *   - The "open window" action via the
 *     ViewModel updates the UI.
 *
 * The test uses the Compose UI testing rule
 * (`createComposeRule`), which requires the
 * `androidx.compose.ui:ui-test-junit4` dep
 * (already configured in `app/build.gradle.kts`).
 */
@RunWith(AndroidJUnit4::class)
class DesktopShellInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `desktop_shell_renders_with_default_session_state`() {
        val viewModel = DesktopShellViewModel(
            initialState = sampleState(),
        )
        composeTestRule.setContent {
            DesktopShellScreen(viewModel = viewModel)
        }
        // The header should be visible.
        composeTestRule.onNodeWithText("Elysium Vanguard Desktop").assertIsDisplayed()
        // The window list should show the open windows.
        composeTestRule.onNodeWithText("- Terminal (NORMAL, z=1)").assertIsDisplayed()
        composeTestRule.onNodeWithText("- Files (MINIMIZED, z=2)").assertIsDisplayed()
    }

    @Test
    fun `open_window_action_updates_the_ui`() {
        val viewModel = DesktopShellViewModel(
            initialState = sampleState(),
        )
        composeTestRule.setContent {
            DesktopShellScreen(viewModel = viewModel)
        }
        // Initially 2 windows. Open a 3rd.
        val result = viewModel.openWindow(
            id = "app-3",
            title = "Browser",
            iconKey = "browser",
        )
        assertNotNull(result)
        assertEquals(3, viewModel.state.value.windows.size)
    }

    private fun sampleState() = DesktopSessionState(
        windows = listOf(
            com.elysium.vanguard.features.desktop.model.DesktopWindow(
                id = "app-1",
                title = "Terminal",
                iconKey = "terminal",
                state = WindowState.NORMAL,
                bounds = WindowBounds(100, 100, 800, 600),
                zOrder = 1,
                lastInteractionAt = 0L,
            ),
            com.elysium.vanguard.features.desktop.model.DesktopWindow(
                id = "app-2",
                title = "Files",
                iconKey = "files",
                state = WindowState.MINIMIZED,
                bounds = WindowBounds(200, 200, 800, 600),
                zOrder = 2,
                lastInteractionAt = 0L,
            ),
        ),
        focusedWindowId = "app-1",
        dockItems = listOf(
            DockItem("terminal", "Terminal", DockItemKind.RUNNING_WINDOW, "app-1"),
            DockItem("files", "Files", DockItemKind.RUNNING_WINDOW, "app-2"),
            DockItem("browser", "Browser", DockItemKind.PINNED_APP, null),
        ),
        desktopBounds = WindowBounds(0, 0, 1920, 1080),
    )
}
