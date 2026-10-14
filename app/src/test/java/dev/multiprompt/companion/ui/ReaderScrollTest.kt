package dev.multiprompt.companion.ui

import android.app.Application

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReaderScrollTest {
    @get:Rule
    val compose = createComposeRule()

    private val session = mutableStateOf("one")
    private val visible = mutableStateOf(true)
    private val messages = mutableStateOf((1..12).map { "Message $it" })
    private val markdown = mutableStateOf("Reply")
    private val viewportHeight = mutableStateOf(240.dp)
    private lateinit var scroll: ScrollState
    private lateinit var scope: CoroutineScope

    @Composable
    private fun Reader() {
        MaterialTheme {
            if (visible.value) {
                scroll = rememberReaderScrollState(session.value)
                scope = rememberCoroutineScope()
                Column(
                    Modifier.height(viewportHeight.value).fillMaxWidth()
                        .testTag("reader").readerScroll(scroll),
                ) {
                    messages.value.forEach { message ->
                        Box(Modifier.height(80.dp)) { Text(message) }
                    }
                    if (messages.value.isNotEmpty()) {
                        MarkdownText(markdown.value, 14.sp, Color.Black, Color.Blue)
                        Text("Newest")
                    }
                }
            }
        }
    }

    private fun assertAtBottom() {
        compose.runOnIdle {
            assertTrue("Fixture must overflow", scroll.maxValue > 0)
            assertEquals(scroll.maxValue, scroll.value)
        }
        compose.onNodeWithText("Newest").assertIsDisplayed()
        compose.onNodeWithText(messages.value.first()).assertIsNotDisplayed()
    }

    private fun scrollUp() {
        compose.onNodeWithTag("reader").performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(scroll.value < scroll.maxValue) }
        compose.onNodeWithText("Newest").assertIsNotDisplayed()
    }

    @Test
    fun freshOpenShowsNewest() {
        compose.setContent { Reader() }
        assertAtBottom()
    }

    @Test
    fun emptyThenCacheThenNetworkRefreshStaysAtBottom() {
        messages.value = emptyList()
        compose.setContent { Reader() }
        compose.runOnIdle { messages.value = (1..12).map { "Cached $it" } }
        assertAtBottom()
        compose.runOnIdle { messages.value = (1..20).map { "Transcript $it" } }
        assertAtBottom()
    }

    @Test
    fun markdownHeightAndViewportChangesStayAtBottom() {
        compose.setContent { Reader() }
        assertAtBottom()
        compose.runOnIdle {
            markdown.value = "# Heading\n\n" + (1..30).joinToString("\n") { "- Paragraph $it" }
        }
        assertAtBottom()
        compose.runOnIdle { viewportHeight.value = 120.dp }
        assertAtBottom()
        compose.runOnIdle { markdown.value = "Short reply" }
        assertAtBottom()
    }

    @Test
    fun refreshWhileReadingOlderMessagesDoesNotJumpToBottom() {
        compose.setContent { Reader() }
        scrollUp()
        val previousOffset = compose.runOnIdle { scroll.value }
        compose.runOnIdle { messages.value = messages.value + "New network message" }
        compose.runOnIdle { assertEquals(previousOffset, scroll.value) }
        compose.onNodeWithText("Newest").assertIsNotDisplayed()
        compose.runOnIdle { markdown.value = "# Expanded\n\n" + "More text\n\n".repeat(20) }
        compose.runOnIdle { assertEquals(previousOffset, scroll.value) }
        compose.onNodeWithText("Newest").assertIsNotDisplayed()
    }

    @Test
    fun lateRowsDuringScrollingAreFollowedWhenScrollingFinishes() {
        compose.setContent { Reader() }
        assertAtBottom()
        val finishScroll = CompletableDeferred<Unit>()
        compose.runOnIdle { scope.launch { scroll.scroll { finishScroll.await() } } }
        compose.runOnIdle {
            assertTrue(scroll.isScrollInProgress)
            messages.value = messages.value + "Arrived during scroll"
        }
        compose.runOnIdle { finishScroll.complete(Unit) }
        assertAtBottom()
    }

    @Test
    fun evenASmallDeliberateScrollIsNotOverriddenByRefresh() {
        compose.setContent { Reader() }
        assertAtBottom()
        compose.onNodeWithTag("reader").performTouchInput {
            down(center)
            moveBy(Offset(0f, 40f), delayMillis = 1_000)
            up()
        }
        val previousOffset = compose.runOnIdle {
            assertTrue(scroll.value < scroll.maxValue)
            scroll.value
        }
        compose.runOnIdle { messages.value = messages.value + "Refresh" }
        compose.runOnIdle { assertEquals(previousOffset, scroll.value) }
    }

    @Test
    fun olderPagesDoNotPullTheReaderToTheBottom() {
        compose.setContent { Reader() }
        scrollUp()
        val previousOffset = compose.runOnIdle { scroll.value }
        compose.runOnIdle { messages.value = listOf("Older page") + messages.value }
        compose.runOnIdle { assertEquals(previousOffset, scroll.value) }
        compose.onNodeWithText("Newest").assertIsNotDisplayed()
    }

    @Test
    fun switchingSessionsWithTheSameHeightStartsAtBottom() {
        compose.setContent { Reader() }
        scrollUp()
        compose.runOnIdle { session.value = "two" }
        assertAtBottom()
        scrollUp()
        compose.runOnIdle { session.value = "one" }
        assertAtBottom()
    }

    @Test
    fun reopeningTheSameWarmSessionStartsAtBottom() {
        compose.setContent { Reader() }
        scrollUp()
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { visible.value = true }
        assertAtBottom()
    }

    @Test
    fun restoringTheScreenDiscardsTheOldReadingPosition() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Reader() }
        scrollUp()
        restoration.emulateSavedInstanceStateRestore()
        assertAtBottom()
    }

    @Test
    fun returningToBottomResumesFollowingLateMessages() {
        compose.setContent { Reader() }
        scrollUp()
        compose.onNodeWithTag("reader").performTouchInput { swipeUp() }
        assertAtBottom()
        compose.runOnIdle { messages.value = messages.value + "Later message" }
        assertAtBottom()
    }
}
