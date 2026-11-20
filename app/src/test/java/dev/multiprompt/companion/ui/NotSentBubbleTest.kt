package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.multiprompt.companion.reader.ChatHistory
import dev.multiprompt.companion.reader.ChatHistoryMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotSentBubbleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun notSentReasonRendersUnderTheNotSentLabel() {
        val detail = "Chat was busy typing, try again"
        compose.setContent {
            MultipromptTheme(readerStyle = true) {
                ChatHistoryList(
                    messages = listOf(ChatHistoryMessage("failed", "user", "Blocked prompt", "", 0, 1, delivery = ChatHistory.NOT_SENT, notSentDetail = detail)),
                    onRemove = {},
                    onSendAgain = { _, _ -> true },
                )
            }
        }
        compose.onNodeWithText("Not sent").assertIsDisplayed()
        compose.onNodeWithText(detail).assertIsDisplayed()
    }
}