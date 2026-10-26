package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.multiprompt.companion.reader.ChatHistoryMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatChoicesTest {
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(scheduler))

    @Test
    fun latestReplyOptionsAreTappable() {
        compose.setContent {
            MaterialTheme {
                ChatHistoryList(messages = listOf(ChatHistoryMessage("latest", "assistant",
                    "Choose a route:\n\n1. **Create a new account.** Keep work separate.\n\n2. **Use your own account.** Keep the current setup.",
                    "", 0, 1)))
            }
        }
        compose.onNodeWithText("Use your own account. Keep the current setup.").assertHasClickAction()
    }
}
