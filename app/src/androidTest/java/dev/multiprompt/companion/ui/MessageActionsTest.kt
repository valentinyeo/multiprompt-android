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
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.captureToImage
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
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
        when (name) {
            "sheet" -> compose.onNode(isDialog()).captureToImage()
            "reply" -> compose.onNodeWithText("Reply to Claude").captureToImage()
            "unread" -> compose.onNodeWithText("New").captureToImage()
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(2500, 15_000)
        output.outputStream().use {
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test
    fun longPressShowsAllSixActions() {
        harness()
        open()
        listOf("Reply", "Mark as unread", "Copy", "Select Text", "Copy Request ID", "Report").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        screenshot("sheet")
        compose.onNodeWithText("Copy").performClick()
        compose.runOnIdle {
            assertEquals("My question", context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString())
        }
        open("First line\nSecond line")
        compose.onNodeWithText("Copy Request ID").performClick()
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
