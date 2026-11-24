package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import dev.multiprompt.companion.reader.ChatHistoryMessage
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
class FollowNewestReplyTest {
    @get:Rule val compose = createComposeRule()
    private val messages = mutableStateOf((1..20).map(::reply))
    private val working = mutableStateOf(false)
    private val follow = mutableStateOf(0)
    private val tick = mutableStateOf(0)
    private lateinit var listState: LazyListState
    private lateinit var follower: NewestFollower

    private fun reply(index: Int) = ChatHistoryMessage("reply-$index", "assistant", "Reply $index", "",
        index.toLong(), index.toLong())

    private fun render() {
        compose.setContent {
            MultipromptTheme(readerStyle = true) {
                Box(Modifier.height(300.dp)) {
                    listState = rememberLazyListState()
                    follower = remember(listState) { NewestFollower(listState) }
                    ChatHistoryList(messages.value, modifier = Modifier.height(300.dp), listState = listState,
                        newestFollower = follower, followRequest = follow.value, working = working.value,
                        sessionName = "session-${tick.value}")
                }
            }
        }
    }

    @Test
    fun newReplyStaysOnScreenWhenTheWorkingDotsLeave() {
        render()
        compose.onNodeWithText("Reply 20").assertIsDisplayed()
        compose.runOnIdle { working.value = true }
        compose.waitForIdle()
        compose.runOnIdle {
            messages.value = messages.value + reply(21)
            working.value = false
        }
        compose.onNodeWithText("Reply 21").assertIsDisplayed()
    }

    @Test
    fun readingOlderRepliesStaysPutUntilJumpingBackToNewest() {
        messages.value = (1..40).map(::reply)
        render()
        compose.onNodeWithTag("chat-history").performScrollToIndex(15)
        compose.waitForIdle()
        val anchor = messages.value.asReversed()[listState.firstVisibleItemIndex].text
        val before = compose.onNodeWithText(anchor).getUnclippedBoundsInRoot()
        val requests = follower.requests
        compose.runOnIdle { messages.value = messages.value + reply(41) }
        assertEquals(before, compose.onNodeWithText(anchor).getUnclippedBoundsInRoot())
        assertEquals("Reading older replies must not follow the newest", requests, follower.requests)
        compose.onNodeWithText("Reply 41").assertDoesNotExist()
        compose.runOnIdle { follow.value++ }
        compose.onNodeWithText("Reply 41").assertIsDisplayed()
        compose.runOnIdle { messages.value = messages.value + reply(42) }
        compose.onNodeWithText("Reply 42").assertIsDisplayed()
    }

    @Test
    fun unchangedNewestReplyNeverRequestsAScroll() {
        render()
        compose.onNodeWithText("Reply 20").assertIsDisplayed()
        val requests = follower.requests
        repeat(20) { index -> compose.runOnIdle { tick.value = index + 1 } }
        compose.waitForIdle()
        assertEquals("Recomposition must not scroll the list", requests, follower.requests)
        compose.runOnIdle { messages.value = messages.value + reply(21) }
        compose.onNodeWithText("Reply 21").assertIsDisplayed()
        assertEquals(requests + 1, follower.requests)
    }
}
