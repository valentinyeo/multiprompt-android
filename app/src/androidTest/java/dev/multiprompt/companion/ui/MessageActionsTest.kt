package dev.multiprompt.companion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import org.junit.Rule
import org.junit.Test

class MessageActionsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun longPressShowsAllSixActions() {
        compose.setContent {
            MaterialTheme {
                ChatHistoryList(messages = listOf(
                    dev.multiprompt.companion.reader.ChatHistoryMessage("message", "user", "My question", "", 0, 1),
                ))
            }
        }
        compose.onNodeWithText("My question").performTouchInput { longClick() }
        listOf("Reply", "Mark as unread", "Copy", "Select Text", "Copy Request ID", "Report").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }
}
