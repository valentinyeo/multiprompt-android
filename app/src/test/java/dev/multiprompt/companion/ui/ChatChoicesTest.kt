package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.multiprompt.companion.ssh.ClaudeQuestion
import dev.multiprompt.companion.ssh.TmuxCommands
import dev.multiprompt.companion.reader.ChatHistoryMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.io.File
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
class ChatChoicesTest {
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(scheduler))
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

    @Test
    fun failedQuestionTransportAllowsRetry() {
        val question = ClaudeQuestion("Choose a route", listOf(ClaudeQuestion.Option(1, "Create"), ClaudeQuestion.Option(2, "Keep")))
        val error = mutableStateOf<String?>(null)
        var attempts = 0
        compose.setContent {
            MaterialTheme {
                QuestionChoices(question, enabled = true, error = error.value) { attempts++; true }
            }
        }
        compose.onNodeWithText("2: Keep").performClick().assertIsNotEnabled()
        compose.runOnIdle { error.value = "Connection failed" }
        compose.onNodeWithText("2: Keep").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, attempts) }
    }

    @Test
    fun terminalQuestionTapReachesTheSessionAsANumberKeyNotPastedText() {
        val screen = """
            ──────────────────────────
            Which route should we use?
            ❯ 1. Create a new account
              2. Use the current account
              3. Type something.
            ──────────────────────────
            Enter to select · Tab/Arrow keys to navigate · Esc to cancel
        """.trimIndent()
        val question = requireNotNull(ClaudeQuestion.parse(screen))
        var received = ""
        compose.setContent {
            MaterialTheme {
                QuestionChoices(question, enabled = true) { index ->
                    val command = TmuxCommands.questionAnswer("question-proof", question, screen, index)
                    val script = sequenceOf(File("../tools/check-question-key.sh"), File("tools/check-question-key.sh")).first { it.isFile }
                    val process = ProcessBuilder("timeout", "15", "bash", script.path, command)
                        .redirectErrorStream(true).start()
                    received = process.inputStream.bufferedReader().readText()
                    check(process.waitFor() == 0) { received }
                    true
                }
            }
        }
        compose.onNodeWithText("2: Use the current account").performClick().assertIsSelected().assertIsNotEnabled()
        compose.runOnIdle { assertEquals("2", received) }
        compose.onNodeWithText("1: Create a new account").assertIsNotEnabled()
    }

}
