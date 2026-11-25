package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.reader.ChatHistoryMessage
import dev.multiprompt.companion.reader.HostChange
import dev.multiprompt.companion.reader.HostSettings
import dev.multiprompt.companion.reader.ModelChoice
import dev.multiprompt.companion.reader.ModelChoices
import dev.multiprompt.companion.reader.PendingQuestion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
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
class ModelChoiceRowTest {
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(scheduler))

    private val question = PendingQuestion("call", 0, "Which reply style?", "Style",
        listOf(PendingQuestion.Option("Concise", "Short answers."), PendingQuestion.Option("Detailed", "Long answers.")),
        multiSelect = false, other = false, provider = "claude")

    @Test
    fun everyStructuredModelIsOnScreenAndPicksItsChange() {
        var picked: ModelChoice? = null
        val choices = ModelChoices.of(AgentKind.CLAUDE, HostSettings("opus", "high", "plan", true))
        compose.setContent {
            MultipromptTheme(readerStyle = true) { ModelChoiceRow(choices) { picked = it } }
        }

        listOf("Opus · current", "Sonnet", "Haiku", "Fable").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithText("Opus · current").assertIsDisplayed()
        compose.onNodeWithText("Sonnet").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(HostChange.SONNET, picked?.change) }
    }

    @Test
    fun aQuestionFromTheHistoryListAnswersWithNoPaneText() {
        var answers = 0
        compose.setContent {
            MultipromptTheme(readerStyle = true) {
                ChatHistoryList(
                    messages = listOf(ChatHistoryMessage("prompt", "user", "What next?", "", 0, 1)),
                    questions = listOf(question),
                    onQuestionChoice = { _, _, _ -> answers++; true },
                )
            }
        }

        listOf("Which reply style?", "Concise", "Detailed").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithText("Concise").assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, answers) }
    }
}
