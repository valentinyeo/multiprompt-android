package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
class InboxRowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun classicRowShowsRobotNamePreviewAndTimeWithoutPerRowMenuButton() {
        compose.setContent { M3TestScreen(M3Screen.INBOX_LIST, material3 = false, sunlight = false) }
        compose.onNodeWithText("claude-reader").assertIsDisplayed()
        compose.onAllNodesWithText("Ready for your next prompt").assertCountEquals(2)
        compose.onNodeWithContentDescription("Robot claude-reader", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(2, compose.onAllNodesWithTag("Session activity", useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Session actions").assertDoesNotExist()
    }

    @Test
    fun botNamesKeepEmojiAndAccents() {
        compose.setContent { EmojiNamesInbox() }
        compose.onNodeWithText("Rocket 🤖 Chief").assertIsDisplayed()
        compose.onNodeWithText("Kraków 🚀 Lead").assertIsDisplayed()
        compose.onNodeWithContentDescription("Robot Rocket 🤖 Chief", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Robot Kraków 🚀 Lead", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun longPressOpensRowActions() {
        compose.setContent { M3TestScreen(M3Screen.INBOX_LIST, material3 = false, sunlight = false) }
        compose.onNodeWithText("Waiting").assertDoesNotExist()
        compose.onNodeWithText("claude-reader").performTouchInput { longClick() }
        compose.onNodeWithText("Waiting").assertIsDisplayed()
        compose.onNodeWithText("End tmux session").assertIsDisplayed()
    }
}
