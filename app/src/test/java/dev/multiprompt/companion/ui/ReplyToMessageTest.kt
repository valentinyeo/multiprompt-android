package dev.multiprompt.companion.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.os.Looper
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.util.ReflectionHelpers
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReplyToMessageTest {
    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(scheduler))

    private val switches = mutableListOf<Int>()
    private val sent = mutableListOf<String>()
    private lateinit var clipboard: ClipboardManager
    private lateinit var view: View

    private fun harness(agentText: String = "First line\nSecond line") {
        compose.setContent {
            MaterialTheme {
                var replyTo by remember { mutableStateOf<String?>(null) }
                var author by remember { mutableStateOf("") }
                val gate = remember { ReplySwipeGate() }
                clipboard = LocalClipboardManager.current
                view = LocalView.current
                Column(
                    Modifier.fillMaxWidth().background(Color.White)
                        .horizontalSwipe({ switches.add(it) }, suppressRight = gate::suppressRight)
                        .verticalScroll(rememberScrollState()).padding(16.dp).testTag("harness"),
                ) {
                    ReplyableBubble(
                        isUser = true,
                        sunlight = true,
                        text = "My question",
                        onReply = { replyTo = it; author = "You" },
                        gate = gate,
                        modifier = Modifier.testTag("user"),
                    ) { Text("You\nMy question") }
                    Spacer(Modifier.height(12.dp))
                    ReplyableBubble(
                        isUser = false,
                        sunlight = true,
                        text = agentText,
                        onReply = { replyTo = it; author = "Claude" },
                        gate = gate,
                        modifier = Modifier.testTag("agent"),
                    ) { Text("Claude\n$agentText") }
                    Spacer(Modifier.fillMaxWidth().height(90.dp).testTag("outside"))
                    replyTo?.let {
                        ReplyQuoteBar(message = it, author = author, onCancel = { replyTo = null })
                    }
                    Button(onClick = { sent.add(ReplyQuote.compose(replyTo, "my answer")) }) {
                        Text("Send")
                    }
                }
            }
        }
    }

    @Test
    fun swipeRightRepliesInsteadOfSwitchingSessionAndSendsQuote() {
        harness()
        compose.onNodeWithTag("agent").performTouchInput { swipeRight() }
        compose.onNodeWithText("Reply to Claude").assertIsDisplayed()
        compose.onNodeWithText("First line Second line").assertIsDisplayed()
        compose.onNodeWithText("Send").performClick()
        compose.runOnIdle {
            assertTrue(switches.isEmpty())
            assertEquals(listOf("> First line\n> Second line\n\nmy answer"), sent)
        }
    }

    @Test
    fun longPressOffersReplyForUserBubble() {
        harness()
        compose.onNodeWithTag("user").performTouchInput { longClick() }
        compose.onNodeWithText("Copy").assertIsDisplayed()
        compose.onNodeWithText("Select Text").assertIsDisplayed()
        compose.onNodeWithText("Reply").performClick()
        compose.onNodeWithText("Reply to You").assertIsDisplayed()
    }

    @Test
    fun cancelReplySendsOnlyTheAnswer() {
        harness()
        compose.onNodeWithTag("agent").performTouchInput { swipeRight() }
        compose.onNodeWithContentDescription("Cancel reply").performClick()
        compose.onNodeWithText("Reply to Claude").assertDoesNotExist()
        compose.onNodeWithText("Send").performClick()
        compose.runOnIdle { assertEquals(listOf("my answer"), sent) }
    }

    @Test
    fun leftSwipeOnBubbleStillSwitchesToNextSession() {
        harness()
        compose.onNodeWithTag("agent").performTouchInput { swipeLeft() }
        compose.onNodeWithContentDescription("Cancel reply").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(1), switches) }
    }

    @Test
    fun rightSwipeOutsideBubbleStillSwitchesToPreviousSession() {
        harness()
        compose.onNodeWithTag("outside").performTouchInput { swipeRight() }
        compose.onNodeWithContentDescription("Cancel reply").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(-1), switches) }
    }

    @Test
    fun elevenLinesAreTruncatedWhenSending() {
        harness((1..11).joinToString("\n") { "Line $it" })
        compose.onNodeWithTag("agent").performTouchInput { swipeRight() }
        compose.onNodeWithText("Reply to Claude").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Send").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(
                listOf("> Line 1\n> Line 2\n> Line 3\n> Line 4\n> Line 5\n> Line 6\n> Line 7\n> Line 8\n> Line 9\n> Line 10\n> …\n\nmy answer"),
                sent,
            )
        }
    }

    @Test
    fun selectTextEnablesOnlyThatBubble() {
        harness()
        compose.onNodeWithTag("user").assert(SemanticsMatcher.expectValue(ReplySelectable, false))
        compose.onNodeWithTag("user").performTouchInput { longClick() }
        compose.onNodeWithText("Select Text").performClick()
        compose.onNodeWithTag("user").assert(SemanticsMatcher.expectValue(ReplySelectable, true))
        compose.onNodeWithTag("agent").assert(SemanticsMatcher.expectValue(ReplySelectable, false))
        compose.onNodeWithText("Reply").assertDoesNotExist()
    }

    @Test
    fun copyCopiesWholeMessageWithoutStartingReply() {
        harness()
        compose.onNodeWithTag("agent").performTouchInput { longClick() }
        compose.onNodeWithText("Copy").performClick()
        compose.onNodeWithText("Reply").assertDoesNotExist()
        compose.onNodeWithContentDescription("Cancel reply").assertDoesNotExist()
        compose.runOnIdle { assertEquals("First line\nSecond line", clipboard.getText()?.text) }
    }

    @Test
    fun captureReplyQuoteBar() {
        assumeTrue(System.getenv("IGSH418_SHOT") != null)
        harness()
        compose.onNodeWithTag("agent").performTouchInput { swipeRight() }
        compose.onNodeWithText("Reply to Claude").assertIsDisplayed()
        val attachInfo = ReflectionHelpers.getField<Any>(view.rootView, "mAttachInfo")
        val hardwareAccelerated = view.rootView.isHardwareAccelerated
        ReflectionHelpers.setField(attachInfo, "mHardwareAccelerated", false)
        val redraw = CoroutineScope(UnconfinedTestDispatcher(scheduler)).launch {
            delay(16)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            val root = view.rootView
            root.draw(Canvas(Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)))
            root.viewTreeObserver.dispatchOnDraw()
            shadowOf(Looper.getMainLooper()).idle()
        }
        val bitmap = try {
            compose.onNodeWithTag("harness").captureToImage().asAndroidBitmap()
        } finally {
            redraw.cancel()
            ReflectionHelpers.setField(attachInfo, "mHardwareAccelerated", hardwareAccelerated)
        }
        val output = File("build/reports/igsh-418-reply.png")
        checkNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(output.length() > 0)
    }
}
