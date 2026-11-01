package dev.multiprompt.companion.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import dev.multiprompt.companion.BuildConfig
import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.model.TmuxSession
import dev.multiprompt.companion.reader.ChatHistory
import dev.multiprompt.companion.reader.ChatHistoryMessage
import dev.multiprompt.companion.reader.ChatReactions
import dev.multiprompt.companion.reader.ChatHistoryProtocol
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MessageActionsTest {
    @get:Rule
    val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val session = TmuxSession("action-test", "claude-proof", 1, 0, 100)
    private val messages = ChatHistory(sid = "proof", inode = 1, rows = listOf(
        ChatHistoryProtocol.Row("user", "My question", "user-request", "", 0, 1),
        ChatHistoryProtocol.Row("assistant", "First line\nSecond line", "assistant-request", "", 1, 2),
    )).messages

    private fun harness() {
        context.getSharedPreferences("session_read_state", Context.MODE_PRIVATE).edit().clear().commit()
        val reads = SessionReadStore(context)
        reads.markRead(session)
        compose.setContent {
            MultipromptTheme(readerStyle = true) {
                var quote by remember { mutableStateOf<String?>(null) }
                var author by remember { mutableStateOf("") }
                var unreadKey by remember { mutableStateOf<String?>(null) }
                Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    Text("Message actions · Claude")
                    ChatHistoryList(
                        messages = messages,
                        modifier = Modifier.weight(1f),
                        sessionName = session.displayName,
                        unreadMessageKey = unreadKey,
                        onReply = { text, user -> quote = text; author = if (user) "You" else "Claude" },
                        onMarkUnread = { key ->
                            reads.markUnreadFrom(session, key)
                            unreadKey = key
                        },
                    )
                    quote?.let { ReplyQuoteBar(it, author, { quote = null }) }
                    Text("Message Claude…")
                }
                }
            }
        }
    }

    private fun open(text: String = "My question") {
        compose.onNodeWithText(text).performTouchInput { longClick() }
    }

    private fun screenshot(name: String) {
        val output = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "message-actions-$name.png")
        check(output.parentFile?.mkdirs() == true || output.parentFile?.isDirectory == true)
        compose.waitForIdle()
        val bitmap = if (name == "sheet") {
            compose.onNode(isDialog()).captureToImage()
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        } else {
            compose.onRoot().captureToImage().asAndroidBitmap()
        }
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test
    fun reactionBadgesMatchDesktopGreysInBothThemes() {
        var sunlight by mutableStateOf(false)
        compose.setContent {
            MultipromptTheme(readerStyle = true, sunlight = sunlight) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding()) {
                        Text("Reaction colours · ${if (sunlight) "Light" else "Dark"}")
                        for (isUser in listOf(false, true)) {
                            ReplyableBubble(
                                isUser = isUser,
                                sunlight = sunlight,
                                text = "Reactions stay inside this message",
                                onReply = {},
                                gate = remember { ReplySwipeGate() },
                                modifier = Modifier.testTag("badge-target-$isUser"),
                                messageId = "palette-$isUser",
                                reactions = ChatReactions.Emoji.entries.toSet(),
                            ) { Text("Reactions stay inside this message") }
                        }
                    }
                }
            }
        }
        for (light in listOf(false, true)) {
            compose.runOnIdle { sunlight = light }
            compose.waitForIdle()
            val fill = if (light) 0xFFE1E1E1.toInt() else 0xFF353535.toInt()
            val outline = if (light) 0xFF9B9B9B.toInt() else 0xFF4A4A4A.toInt()
            for (isUser in listOf(false, true)) {
                val badge = compose.onNodeWithTag("reaction-badges:palette-$isUser", useUnmergedTree = true)
                badge.assertIsDisplayed()
                val pixels = badge.captureToImage().toPixelMap()
                val colours = (0 until pixels.height).flatMap { y ->
                    (0 until pixels.width).map { x -> pixels[x, y].toArgb() }
                }
                assertTrue("Desktop grey badge fill missing: light=$light user=$isUser", colours.count { it == fill } > 100)
                assertTrue("Desktop grey badge outline missing: light=$light user=$isUser", colours.count { it == outline } > 10)
                val parent = compose.onNodeWithTag("badge-target-$isUser").fetchSemanticsNode().boundsInRoot
                val bounds = badge.fetchSemanticsNode().boundsInRoot
                assertTrue("Reaction badge outside its message", bounds.left >= parent.left && bounds.right <= parent.right && bounds.top >= parent.top && bounds.bottom <= parent.bottom)
            }
            screenshot("reaction-grey-${if (light) "light" else "dark"}")
        }
    }

    @Test
    fun reactionLinesNeverLeakIntoUserBubbles() {
        val reply = ChatHistoryMessage("target", "assistant", "Synthetic reply for a thumbs up", "", 0, 1, "msg_target")
        val line = ChatReactions.format(ChatReactions.Emoji.LIKE, reply.requestId, reply.text)!!
        val words = "My follow-up stays visible"
        val mixed = ChatHistoryMessage("mixed", "user", "$words\n\n$line", "", 1, 2)
        val reactionOnly = ChatHistoryMessage("only-reaction", "user", "\n$line\n", "", 2, 3)
        val raw = listOf(reply, mixed, reactionOnly)
        compose.setContent {
            MultipromptTheme(readerStyle = true) {
                Surface(Modifier.fillMaxSize()) { ChatHistoryList(raw) }
            }
        }
        screenshot("reaction-display")
        compose.onNodeWithText(words).assertIsDisplayed()
        compose.onNodeWithContentDescription("Reactions: 👍").assertIsDisplayed()
        compose.onNodeWithTag("chat-message-only-reaction").assertDoesNotExist()
        compose.onNodeWithText(line, substring = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals("$words\n\n$line", mixed.transcript().text) }
    }

    @Test
    fun longPressShowsAllSixActions() {
        harness()
        open()
        listOf("Reply", "Mark as unread", "Copy", "Select Text", "Copy request ID", "Report").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        screenshot("sheet")
        compose.onNodeWithText("Copy").performClick()
        compose.runOnIdle {
            assertEquals("My question", context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString())
        }
        open("First line\nSecond line")
        compose.onNodeWithText("Copy request ID").performClick()
        compose.runOnIdle {
            assertEquals("assistant-request", context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString())
        }
        open("First line\nSecond line")
        compose.onNodeWithText("Reply").performClick()
        compose.onNodeWithText("Reply to Claude").assertIsDisplayed()
        compose.onNodeWithText("First line Second line").assertIsDisplayed()
        screenshot("reply")
        compose.runOnIdle { assertEquals("> First line\n> Second line\n\nFollow up", ReplyQuote.compose("First line\nSecond line", "Follow up")) }
        open()
        compose.onNodeWithText("Mark as unread").performClick()
        compose.onNodeWithText("New").assertIsDisplayed()
        compose.runOnIdle {
            val reloaded = SessionReadStore(context)
            assertTrue(reloaded.isUnread(session))
            assertEquals(messages.first().key, reloaded.unreadMessageKey(session))
            assertTrue(reloaded.unreadCountState(session).count >= 1)
        }
        screenshot("unread")
    }

    @Test
    fun selectAndReportUseTheFullMessage() {
        harness()
        open()
        compose.onNodeWithText("Select Text").performClick()
        compose.onNodeWithText("Done").assertIsDisplayed().performClick()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var sent: Intent? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                sent = intent
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            open()
            compose.onNodeWithText("Report").performClick()
            compose.runOnIdle {
                assertEquals(Intent.ACTION_CHOOSER, sent?.action)
                val share = sent?.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                assertEquals(Intent.ACTION_SEND, share?.action)
                assertEquals("text/plain", share?.type)
                val report = share?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                listOf("My question", session.displayName, "user-request", BuildConfig.VERSION_NAME).forEach {
                    assertTrue(report.contains(it))
                }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }
}
