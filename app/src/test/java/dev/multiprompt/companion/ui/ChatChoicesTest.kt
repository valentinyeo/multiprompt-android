package dev.multiprompt.companion.ui


@OptIn(ExperimentalCoroutinesApi::class)
import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.multiprompt.companion.reader.ChatHistoryMessage
import dev.multiprompt.companion.reader.PendingQuestion
import dev.multiprompt.companion.ssh.TmuxCommands
import java.io.File
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatChoicesTest {
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(scheduler))
    private val question = PendingQuestion("call", 0, "Which route?", "Account",
        listOf(PendingQuestion.Option("Create", "Keep work separate."), PendingQuestion.Option("Keep", "Current setup.")),
        multiSelect = false, other = true, provider = "claude")

    private val first = "Should the two daily jobs keep running, or pause until you top up?"
    private val second = "Which account should pay, and when should the jobs restart?"
    private val third = "Keep the current plan, or review all three open questions first?"

    private fun renderList(readerStyle: Boolean, marker: String = "1.") {
        val reply = "Still open, from my last message:\n\n$marker $first\n2. $second\n3. $third"
        compose.setContent {
            MultipromptTheme(readerStyle = readerStyle) {
                ChatHistoryList(messages = listOf(ChatHistoryMessage("latest", "assistant", reply, "", 0, 1)))
            }
        }
    }

    @Test
    fun readerNumberedReplyKeepsNumbersAndCompleteReadOnlyText() {
        renderList(readerStyle = true)
        listOf("1.", "2.", "3.", first, second, third).forEach {
            compose.onNodeWithText(it).assertExists().assertHasNoClickAction()
        }
        listOf("A", "B", "C").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test
    fun classicNumberedReplyKeepsCompleteReadOnlyText() {
        renderList(readerStyle = false)
        listOf("1.", "2.", "3.", first, second, third).forEach {
            compose.onNodeWithText(it).assertExists().assertHasNoClickAction()
        }
    }

    @Test
    fun letteredReplyDoesNotBecomeAnAnswerCard() {
        renderList(readerStyle = true, marker = "A.")
        compose.onNodeWithText("A.").assertExists().assertHasNoClickAction()
        compose.onNodeWithText(first).assertExists().assertHasNoClickAction()
    }

    @Test fun exactQuestionAndDescriptionsStaySeparateFromLabels() {
        var answer: Pair<Set<Int>, String>? = null
        compose.setContent {
            MaterialTheme { QuestionChoices(question, enabled = true) { selected, other -> answer = selected to other; true } }
        }
        listOf("Account", "Which route?", "Create", "Keep work separate.", "Keep", "Current setup.", "Other").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithText("Keep").performClick()
        compose.onNodeWithText("Submit answer").performClick().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(setOf(1) to "", answer) }
    }

    @Test fun multipleSelectionsAndOtherArePreservedTogether() {
        var answer: Pair<Set<Int>, String>? = null
        compose.setContent {
            MaterialTheme { QuestionChoices(question.copy(multiSelect = true), enabled = true) { selected, other -> answer = selected to other; true } }
        }
        compose.onNodeWithText("Create").performClick()
        compose.onNodeWithText("Keep").performClick()
        compose.onNodeWithText("Other").performTextInput("A third route")
        compose.onNodeWithText("Submit answer").performClick()
        compose.runOnIdle { assertEquals(setOf(0, 1) to "A third route", answer) }
    }

    @Test fun singleChoiceAndOtherAreExclusive() {
        var answer: Pair<Set<Int>, String>? = null
        compose.setContent {
            MaterialTheme { QuestionChoices(question, enabled = true) { selected, other -> answer = selected to other; true } }
        }
        compose.onNodeWithText("Create").performClick()
        compose.onNodeWithText("Other").performTextInput("Custom answer")
        compose.onNodeWithText("Submit answer").performClick()
        compose.runOnIdle { assertEquals(emptySet<Int>() to "Custom answer", answer) }
    }

    @Test fun failedQuestionTransportAllowsRetry() {
        val error = mutableStateOf<String?>(null)
        var attempts = 0
        compose.setContent {
            MaterialTheme { QuestionChoices(question, enabled = true, error = error.value) { _, _ -> attempts++; true } }
        }
        compose.onNodeWithText("Keep").performClick()
        compose.onNodeWithText("Submit answer").performClick().assertIsNotEnabled()
        compose.runOnIdle { error.value = "Connection failed" }
        compose.onNodeWithText("Submit answer").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, attempts) }
    }
}
