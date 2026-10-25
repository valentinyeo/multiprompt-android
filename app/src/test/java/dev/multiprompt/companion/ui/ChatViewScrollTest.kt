package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import dev.multiprompt.companion.reader.ChatHistoryMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.*
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
class ChatViewScrollTest {
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(scheduler))
    private val messages = mutableStateOf((0 until 560).map(::message))
    private lateinit var listState: LazyListState
    private val nearTop = mutableListOf<Boolean>()
    private val replies = mutableListOf<String>()

    private fun message(index: Int) = ChatHistoryMessage("message-$index", if (index % 2 == 0) "user" else "assistant",
        "Message $index € ✔", "", index * 10L, index * 10L + 10)

    private fun render(loaded: Boolean = true, queued: List<String> = emptyList()) {
        compose.setContent {
            MaterialTheme {
                listState = rememberLazyListState()
                Box(Modifier.height(520.dp)) {
                    ChatHistoryList(messages.value, listState = listState, loaded = loaded,
                        queuedMessages = queued, onNearTop = { nearTop.add(it) },
                        onReply = { text, _ -> replies.add(text) })
                }
            }
        }
    }

    private fun anchor(): Pair<Any, Int> = listState.layoutInfo.visibleItemsInfo
        .maxBy { it.index }.let { it.key to it.offset }

    @Test
    fun wholeConversationScrollsToFirstWithoutButtonsAndKeepsAnchorOnAppendAndPrepend() {
        render()
        compose.onNodeWithText("Load older messages").assertDoesNotExist()
        compose.onNodeWithText("Load more").assertDoesNotExist()
        compose.onNodeWithText("Message 559 € ✔").assertIsDisplayed()
        compose.onNodeWithTag("chat-history").performScrollToIndex(559)
        compose.onNodeWithText("Message 0 € ✔").assertIsDisplayed()
        compose.runOnIdle { assertTrue(nearTop.contains(true)) }
        compose.onNodeWithTag("chat-history").performScrollToIndex(300)
        lateinit var original: Pair<Any, Int>
        compose.runOnIdle {
            original = anchor()
            messages.value = messages.value + message(560)
        }
        compose.runOnIdle { assertEquals(original, anchor()) }
        compose.runOnIdle { messages.value = (-30 until 0).map(::message) + messages.value }
        compose.runOnIdle { assertEquals(original, anchor()) }
        compose.onNodeWithTag("chat-history").performScrollToIndex(messages.value.lastIndex)
        compose.onNodeWithText("Message -30 € ✔").assertIsDisplayed()
    }

    @Test
    fun followsNewMessagesAndStreamedTextOnlyWhenAlreadyAtBottom() {
        render()
        compose.runOnIdle { messages.value = messages.value + message(560) }
        compose.onNodeWithText("Message 560 € ✔").assertIsDisplayed()
        compose.runOnIdle {
            messages.value = messages.value.dropLast(1) + message(560).copy(text = "Streamed newest answer")
        }
        compose.onNodeWithText("Streamed newest answer").assertIsDisplayed()
        compose.runOnIdle { assertFalse(listState.canScrollBackward) }
    }

    @Test
    fun loadingIsPlainAndQueuedMessagesRemainReplyable() {
        messages.value = emptyList()
        render(loaded = false, queued = listOf("Queued question"))
        compose.onNodeWithText("Queued").assertIsDisplayed()
        compose.onNodeWithText("Queued question").performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(listOf("Queued question"), replies) }
    }

    @Test
    fun noHistoryNeverFallsBackToTerminalText() {
        messages.value = emptyList()
        render(loaded = false)
        compose.onNodeWithText("Loading conversation…").assertIsDisplayed()
        compose.onNodeWithText("Load older messages").assertDoesNotExist()
        compose.onNodeWithText("No recent output").assertDoesNotExist()
    }
}
