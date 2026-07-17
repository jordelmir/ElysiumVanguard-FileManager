package com.elysium.vanguard.core.runtime.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.elysium.vanguard.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * PHASE 44 — end-to-end coverage for [MainScreen].
 *
 * This is the only piece of the new runtime
 * still missing instrumented coverage. The
 * test:
 *
 *  1. launches `MainActivity` with the real
 *     Hilt graph (the `RuntimeModule` from
 *     Phase 36 wires the production
 *     collaborators),
 *  2. drives the [MainScreen] via Compose UI
 *     testing,
 *  3. asserts the screen renders the title +
 *     the empty state (no workspaces yet),
 *  4. taps the TopAppBar's "Add" icon to
 *     open [CreateWorkspaceDialog],
 *  5. types a name, taps "Create", and
 *     asserts the new workspace card
 *     appears in the list,
 *  6. opens the workspace's 3-dot menu and
 *     asserts the Pause / Activate / Close
 *     items are present.
 *
 * Phase 39's `WorkspaceManager publishes its
 * own events` is the architectural
 * pre-condition: tapping Create flows
 * MainScreen → WorkspacesViewModel →
 * WorkspaceManager → bus event →
 * WorkspacesViewModel subscriber (refresh) →
 * MainScreen recomposes. The test exercises
 * the full path on a real device.
 *
 * The test is `connectedAndroidTest` (needs an
 * emulator / device). Run via
 * `./gradlew :app:connectedDebugAndroidTest`.
 */
@HiltAndroidTest
class MainScreenInstrumentedTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun mainScreen_rendersEmptyState_whenNoWorkspaces() {
        composeRule
            .onNodeWithText("Sovereign Runtime")
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("No workspaces yet")
            .assertIsDisplayed()
    }

    @Test
    fun mainScreen_createWorkspace_addsToList() {
        // Open the Create dialog via the TopAppBar's
        // Add icon. contentDescription is "Create
        // workspace".
        composeRule
            .onNodeWithText("Create workspace")
            .performClick()
        // Type a name into the dialog's text field.
        composeRule
            .onNodeWithText("Name")
            .performTextInput("My first workspace")
        // Tap the dialog's "Create" confirm button.
        composeRule
            .onNodeWithText("Create")
            .performClick()
        // The new workspace card should now be
        // visible. The WorkspaceCard shows the
        // workspace's name (not the literal "My
        // first workspace" we typed) but the
        // name is what the user sees.
        composeRule
            .onNodeWithText("My first workspace")
            .assertIsDisplayed()
    }

    @Test
    fun mainScreen_workspaceCard_hasMenuWithPauseActivateClose() {
        // Open the Create dialog first to add a
        // workspace, then assert the menu items.
        composeRule
            .onNodeWithText("Create workspace")
            .performClick()
        composeRule
            .onNodeWithText("Name")
            .performTextInput("Menu test")
        composeRule
            .onNodeWithText("Create")
            .performClick()
        // The 3-dot menu's contentDescription is
        // "Workspace menu". Tap it.
        composeRule
            .onNodeWithText("Workspace menu")
            .performClick()
        // The menu shows Pause / Activate / Close
        // for an Active workspace.
        composeRule.onNodeWithText("Pause").assertIsDisplayed()
        composeRule.onNodeWithText("Activate").assertIsDisplayed()
        composeRule.onNodeWithText("Close").assertIsDisplayed()
    }
}
