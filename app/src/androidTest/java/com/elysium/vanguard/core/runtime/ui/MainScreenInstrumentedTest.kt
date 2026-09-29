package com.elysium.vanguard.core.runtime.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.elysium.vanguard.MainScreenTestHost
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * PHASE 44 (updated) — end-to-end coverage for [MainScreen].
 *
 * The app launches into `splash` → `dashboard`
 * (Phase 10.2); `MainScreen` lives behind the
 * `runtime_main` route. The test mounts [MainScreen]
 * directly on the [MainScreenTestHost] Hilt activity —
 * driving the real NavHost is impractical here: the
 * splash uses real-time delays and the dashboard runs
 * infinite animations Espresso can never idle through.
 *
 * Covered:
 *
 *  1. the screen renders the title + the empty
 *     state (no workspaces yet),
 *  2. the TopAppBar "Add" icon (contentDescription
 *     "Create workspace") opens [CreateWorkspaceDialog],
 *     typing a name + tapping "Create" adds a card,
 *  3. the workspace's 3-dot menu exposes the
 *     Pause action (for an Active workspace) and
 *     Activate appears once the workspace is paused.
 *
 * Every test starts from a deterministic empty
 * state: [WorkspaceManager.deleteWorkspace] removes
 * whatever earlier runs left behind (the manager
 * hydrates from disk across process restarts).
 *
 * Requires a device/emulator:
 * `./gradlew :app:connectedDebugAndroidTest`.
 */
@HiltAndroidTest
class MainScreenInstrumentedTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainScreenTestHost>()

    @Inject
    lateinit var workspaceManager: WorkspaceManager

    @Before
    fun setup() {
        hiltRule.inject()
        // Deterministic empty state — workspaces
        // persist in filesDir across test runs.
        workspaceManager.listWorkspaces().forEach {
            workspaceManager.deleteWorkspace(it.id)
        }
    }

    /**
     * Poll for a text node WITHOUT Espresso idling:
     * the splash + dashboard run infinite animations,
     * so the main looper never reaches Espresso's idle
     * state and `waitUntil` would hang for 60s.
     * `fetchSemanticsNodes` reads the current semantics
     * tree directly, so it works during animations.
     */
    private fun waitUntilTextExists(text: String, timeoutMs: Long = 20_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val found = try {
                composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            } catch (_: IllegalStateException) {
                // No compose hierarchy yet (activity still
                // starting) — keep polling.
                false
            }
            if (found) return
            Thread.sleep(200)
        }
        throw AssertionError("Timed out waiting for text: $text")
    }

    private fun openMainScreen() {
        // Mount MainScreen directly on the Hilt test host —
        // avoids splash's real-time delays, the dashboard's
        // infinite animations (which Espresso can never idle
        // through), and its LazyVerticalGrid (WORKSPACES tile
        // isn't even composed until scrolled).
        composeRule.setContent {
            MainScreen(onBack = {})
        }
        waitUntilTextExists("Sovereign Runtime")
    }

    @Test
    fun mainScreen_rendersEmptyState_whenNoWorkspaces() {
        openMainScreen()
        composeRule
            .onNodeWithText("Sovereign Runtime")
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("No workspaces yet")
            .assertIsDisplayed()
    }

    @Test
    fun mainScreen_createWorkspace_addsToList() {
        openMainScreen()
        // The TopAppBar's Add icon carries the
        // contentDescription "Create workspace".
        composeRule
            .onNodeWithContentDescription("Create workspace")
            .performClick()
        // Type a name into the dialog's text field
        // (the node with the SetText action).
        composeRule
            .onNode(hasSetTextAction())
            .performTextInput("My first workspace")
        // Tap the dialog's "Create" confirm button.
        composeRule
            .onNodeWithText("Create")
            .performClick()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithText("My first workspace")
            .assertIsDisplayed()
    }

    @Test
    fun mainScreen_workspaceCard_menuPauseActivateClose() {
        openMainScreen()
        composeRule
            .onNodeWithContentDescription("Create workspace")
            .performClick()
        composeRule
            .onNode(hasSetTextAction())
            .performTextInput("Menu test")
        composeRule
            .onNodeWithText("Create")
            .performClick()
        composeRule.waitForIdle()

        // The 3-dot menu's contentDescription is
        // "Workspace menu". One workspace → one node.
        composeRule
            .onNodeWithContentDescription("Workspace menu")
            .performClick()
        // An Active workspace offers Pause + Close
        // (Activate only appears once paused).
        composeRule.onNodeWithText("Pause").assertIsDisplayed()
        composeRule.onNodeWithText("Close").assertIsDisplayed()

        // Pause it; the menu re-renders with Activate.
        composeRule.onNodeWithText("Pause").performClick()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithContentDescription("Workspace menu")
            .performClick()
        composeRule.onNodeWithText("Activate").assertIsDisplayed()
        composeRule.onNodeWithText("Close").assertIsDisplayed()
    }
}
