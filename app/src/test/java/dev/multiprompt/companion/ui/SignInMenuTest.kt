package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SignInMenuTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun overflowOpensSettingsWithoutConfigurationLinks() {
        var settingsCalls = 0
        compose.setContent {
            MaterialTheme {
                InboxOverflowMenu(
                    onSettings = { settingsCalls++ },
                    onArrangeSplits = {}, onNewWorkspace = {}, onRefresh = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Session list actions").performClick()
        compose.onNodeWithText("Hosts").assertDoesNotExist()
        compose.onNodeWithText("Workspaces").assertDoesNotExist()
        compose.onNodeWithText("Update").assertDoesNotExist()
        compose.onNodeWithText("Settings").assertIsDisplayed().performClick()
        compose.onNodeWithText("Settings").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, settingsCalls) }
    }
}
