package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
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
    private val options = "Choose a route:\n\n1. **Create a new account.** Keep work separate.\n\n2. **Use your own account.** Keep the current setup."
    private val first = "Create a new account. Keep work separate."
    private val second = "Use your own account. Keep the current setup."
    private val messages = mutableStateOf(listOf(ChatHistoryMessage("latest", "assistant", options, "", 0, 1)))
    private val queued = mutableStateOf(emptyList<String>())
    private val sent = mutableListOf<String>()

    private fun render(readerStyle: Boolean = false, accept: Boolean = true) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReaderStyle provides readerStyle) {
                    ChatHistoryList(messages = messages.value, queuedMessages = queued.value,
                        onChoice = { if (accept) sent.add(it); accept })
                }
            }
        }
    }

    @Test
    fun latestReplyOptionsAreTappable() {
        render()
        compose.onNodeWithText(second).assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(listOf("2: $second"), sent) }
        compose.onNodeWithText(second).assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithText(first).assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, sent.size) }
    }

    @Test
    fun readerNumberBadgesSendTheirDisplayedLetterAndKeepSelectionWhenOlder() {
        render(readerStyle = true)
        compose.onNodeWithText(second).performClick()
        compose.runOnIdle {
            assertEquals(listOf("B: $second"), sent)
            messages.value = messages.value + ChatHistoryMessage("user", "user", sent.single(), "", 1, 2)
        }
        compose.onNodeWithText(second).assertIsSelected().assertIsNotEnabled()
    }

    @Test
    fun letteredOptionsSendTheirLabelAndDescription() {
        messages.value = listOf(messages.value.single().copy(text = "A) **Create a new account.** Keep work separate.\nB) **Use your own account.** Keep the current setup."))
        render()
        compose.onNodeWithText(second).performClick()
        compose.runOnIdle { assertEquals(listOf("B: $second"), sent) }
    }

    @Test
    fun olderAssistantOptionsAreReadOnlyAfterAnotherReply() {
        messages.value = messages.value + ChatHistoryMessage("newer", "assistant", "A new reply", "", 1, 2)
        render()
        compose.onNodeWithText(second).assertHasNoClickAction()
        compose.runOnIdle { assertEquals(emptyList<String>(), sent) }
    }

    @Test
    fun userReplyOrQueuedAnswerDisablesOldChoices() {
        queued.value = listOf("An answer is queued")
        render()
        compose.onNodeWithText(second).assertHasNoClickAction()
        compose.runOnIdle {
            queued.value = emptyList()
            messages.value = messages.value + ChatHistoryMessage("user", "user", "My answer", "", 1, 2)
        }
        compose.onNodeWithText(second).assertHasNoClickAction()
    }

    @Test
    fun failedEnqueueLeavesChoicesAvailable() {
        render(accept = false)
        compose.onNodeWithText(second).performClick().assertHasClickAction()
        compose.runOnIdle { assertEquals(emptyList<String>(), sent) }
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
