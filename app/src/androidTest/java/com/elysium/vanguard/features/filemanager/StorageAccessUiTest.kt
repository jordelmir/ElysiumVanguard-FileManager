package com.elysium.vanguard.features.filemanager

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PHASE 10.2b — pins the storage-access UX contract so the "hidden files"
 * fix can't regress silently:
 *
 *  - the one-time dialog explains WHY full access is needed (ZArchiver
 *    baseline) and routes both actions (grant chain / dismiss)
 *  - the honest placeholder replaces fake "no files" empty states when
 *    access is off, and its button triggers the grant flow
 *
 * Pure composables → no Hilt graph or ViewModel required.
 */
@RunWith(AndroidJUnit4::class)
class StorageAccessUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `dialog_explains_the_zarchiver_baseline_and_grant_routes_to_the_flow`() {
        var grantCalls = 0
        composeTestRule.setContent {
            var show by remember { mutableStateOf(true) }
            if (show) {
                StorageAccessDialog(
                    onGrant = {
                        grantCalls++
                        show = false
                    },
                    onLater = { show = false },
                )
            }
        }
        composeTestRule.onNodeWithText("VER TODOS TUS ARCHIVOS").assertIsDisplayed()
        // The WHY mentions hidden files + ZArchiver explicitly.
        composeTestRule
            .onNodeWithText("ZArchiver", substring = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("CONCEDER ACCESO").assertIsDisplayed()

        composeTestRule.onNodeWithText("CONCEDER ACCESO").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, grantCalls)
        // Dialog dismissed by the caller state → node gone.
        composeTestRule.onNodeWithText("VER TODOS TUS ARCHIVOS").assertDoesNotExist()
    }

    @Test
    fun `dialog_later_dismisses_without_triggering_the_grant_flow`() {
        var grantCalls = 0
        var laterCalls = 0
        composeTestRule.setContent {
            var show by remember { mutableStateOf(true) }
            if (show) {
                StorageAccessDialog(
                    onGrant = { grantCalls++ },
                    onLater = {
                        laterCalls++
                        show = false
                    },
                )
            }
        }
        composeTestRule.onNodeWithText("AHORA NO").performClick()
        composeTestRule.waitForIdle()

        assertEquals(0, grantCalls)
        assertEquals(1, laterCalls)
        composeTestRule.onNodeWithText("VER TODOS TUS ARCHIVOS").assertDoesNotExist()
    }

    @Test
    fun `placeholder_tells_the_truth_about_hidden_files_and_requests_access`() {
        var grantCalls = 0
        composeTestRule.setContent {
            StorageAccessRequiredPlaceholder(onGrant = { grantCalls++ })
        }
        composeTestRule.onNodeWithText("SIN ACCESO AL ALMACENAMIENTO").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("el sistema los está ocultando", substring = true)
            .assertIsDisplayed()

        composeTestRule.onNodeWithText("CONCEDER ACCESO").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, grantCalls)
    }
}
