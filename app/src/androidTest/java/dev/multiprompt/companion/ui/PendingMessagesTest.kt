package dev.multiprompt.companion.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import org.json.JSONObject
import org.junit.Assert.assertTrue
import java.io.File
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.multiprompt.companion.MultipromptApplication
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.reader.ChatHistory
import dev.multiprompt.companion.reader.ChatHistoryActorTest
import dev.multiprompt.companion.reader.ChatHistoryConnection
import dev.multiprompt.companion.reader.ChatHistoryProtocol
import dev.multiprompt.companion.reader.ChatHistoryState
import dev.multiprompt.companion.reader.MessageJournal
import dev.multiprompt.companion.reader.MessageProtocol
import dev.multiprompt.companion.reader.MessageReplica
import dev.multiprompt.companion.reader.SessionReaderConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test

class PendingMessagesTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var connection: SessionReaderConnection
    private lateinit var history: MutableStateFlow<ChatHistoryState>
    private val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "pending-proof", "/pending")
    private val id = "1234567890123456"
    private fun row(state: MessageProtocol.State) = MessageProtocol.Row(id, "claude", "proof", "Synthetic prompt", 1,
        "attempt", state, null, null, 1, 1.0)

    @Suppress("UNCHECKED_CAST")
    private fun harness() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MultipromptApplication
        val host = HostProfile("pending-proof", "Proof", "", port = 0, username = "proof")
        val stream = ChatHistoryConnection(app.sshRepository, host, identity, app.chatHistoryScript, app.chatHistoryCache)
        connection = SessionReaderConnection(app.sshRepository, host, identity.tmux, identity.agent, app.chatHistoryScript, app.chatHistoryCache)
        connection.javaClass.getDeclaredField("historyIdentity").apply { isAccessible = true }.set(connection, identity)
        connection.javaClass.getDeclaredField("historyConnection").apply { isAccessible = true }.set(connection, stream)
        history = stream.javaClass.getDeclaredField("_state").apply { isAccessible = true }.get(stream) as MutableStateFlow<ChatHistoryState>
        history.value = ChatHistoryState(ChatHistory(sid = "proof", inode = 1, offset = 100), loaded = true,
            replica = MessageReplica(1, 1, mapOf(id to row(MessageProtocol.State.QUEUED))))
        compose.setContent {
            MultipromptTheme(readerStyle = true) {
                val reader by connection.state.collectAsState()
                ChatHistoryView(connection, reader, identity.agent, identity.cwd, 1f, false,
                    ReplySwipeGate(), { _, _ -> }, {}, rememberLazyListState(), 0, "Claude", null, {})
            }
        }
    }

    @Test fun queuedAndAcceptedRemainHostCopies() {
        harness()
        compose.onNodeWithText("Queued").assertIsDisplayed()
        compose.runOnIdle {
            history.value = history.value.copy(replica = MessageReplica(1, 2, mapOf(id to row(MessageProtocol.State.ACCEPTED))))
        }
        compose.onNodeWithText("Accepted").assertIsDisplayed()
        compose.onNodeWithText("Synthetic prompt").assertIsDisplayed()
        compose.onNodeWithText("Send again").assertDoesNotExist()
        compose.onNodeWithText("Remove").assertDoesNotExist()
    }

    @Test fun cancelledRetryShowsRecoveryWithoutBody() {
        harness()
        compose.runOnIdle {
            val entry = MessageJournal.Entry("retry123456789012", "account", MessageProtocol.Scope("claude", "proof", "pending-proof"),
                "retry", id, null, 1, "attempt", true)
            history.value = history.value.copy(replica = MessageReplica(1, 2, mapOf(id to row(MessageProtocol.State.CANCELLED))), unresolved = listOf(entry))
        }
        compose.onNodeWithText("Synthetic prompt").assertDoesNotExist()
        compose.onNodeWithText("Send again").performClick()
        compose.onNodeWithText("Your agent may already have this message. Sending again may duplicate it. Send again?").assertIsDisplayed()
    }

    @Test fun cancelledRetryRecoveryUsesVisibleControlAndProductionOwner() {
        val f = ChatHistoryActorTest().Fixture("claude")
        f.row = f.row("failed")
        f.dropRetryAfterAdmission = true
        val first = f.connection()
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MultipromptApplication
        var owner: ChatHistoryConnection? = null
        try {
            runBlocking {
                first.select(true); first.start()
                val ready = f.ready(first)
                val failed = requireNotNull(ready.replica?.messages?.get(id))
                assertTrue(withTimeout(10_000) { first.retryFailed(failed, true) } is dev.multiprompt.companion.reader.MessageActionResult.Unknown)
            }
            val retryId = requireNotNull(f.admittedRetryId)
            first.close()
            runBlocking {
                f.exchange(MessageProtocol.request(700, f.wire, "message_reset", JSONObject().put("reset_id", "otherreset123456789")))
            }
            val current = f.connection()
            owner = current
            runBlocking {
                current.select(true); current.start(); f.ready(current)
                assertTrue(withTimeout(10_000) { current.resetConversation().await() } == dev.multiprompt.companion.reader.MessageActionResult.BlockedRecovery)
            }
            connection = SessionReaderConnection(app.sshRepository, f.host, f.identity.tmux, f.identity.agent, app.chatHistoryScript, app.chatHistoryCache)
            connection.javaClass.getDeclaredField("historyIdentity").apply { isAccessible = true }.set(connection, f.identity)
            connection.javaClass.getDeclaredField("historyConnection").apply { isAccessible = true }.set(connection, current)
            compose.setContent {
                MultipromptTheme(readerStyle = true) {
                    val reader by connection.state.collectAsState()
                    ChatHistoryView(connection, reader, f.identity.agent, f.identity.cwd, 1f, false,
                        ReplySwipeGate(), { _, _ -> }, {}, rememberLazyListState(), 0, "Claude", null, {})
                }
            }
            compose.onNodeWithText("hello").assertDoesNotExist()
            compose.onNodeWithText("Send again").performClick()
            compose.onNodeWithText("Your agent may already have this message. Sending again may duplicate it. Send again?").assertIsDisplayed()
            compose.onAllNodesWithText("Send again").onLast().performClick()
            compose.waitUntil(10_000) { f.journal.entries().isEmpty() }
            assertTrue(f.lastRetryId == retryId && f.retryCalls == 2 && f.retryAdmissions == 1)
            compose.onNodeWithText("hello").assertDoesNotExist()
            runBlocking {
                assertTrue(withTimeout(10_000) { current.resetConversation().await() } == dev.multiprompt.companion.reader.MessageActionResult.Applied)
            }
        } finally {
            first.close()
            owner?.close()
        }
    }

    private fun contrast(bitmap: Bitmap): Double {
        val values = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(values, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val light = values.map { Color.red(it) * 0.2126 + Color.green(it) * 0.7152 + Color.blue(it) * 0.0722 }
        return light.max() - light.min()
    }

    private fun pixels() = compose.onNodeWithText("Synthetic prompt").captureToImage().asAndroidBitmap()

    private fun save(name: String, bitmap: Bitmap) {
        val output = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "pending-messages")
        output.mkdirs()
        File(output, "$name-text.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        File(output, "$name.png").outputStream().use {
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun screenshot(name: String) {
        val output = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "pending-messages")
        output.mkdirs()
        File(output, "$name.png").outputStream().use {
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun hostAcceptanceRestoresBrightnessBeforeTranscript() {
        harness()
        compose.runOnIdle {
            history.value = history.value.copy(replica = MessageReplica(1, 1, mapOf(id to row(MessageProtocol.State.ACCEPTED))))
        }
        val normal = pixels()
        val normalContrast = contrast(normal)
        save("normal", normal)
        compose.runOnIdle {
            history.value = history.value.copy(replica = MessageReplica(1, 1, mapOf(id to row(MessageProtocol.State.QUEUED))))
        }
        val pending = pixels()
        val pendingContrast = contrast(pending)
        save("pending", pending)
        screenshot("before-pending")
        compose.runOnIdle {
            val other = row(MessageProtocol.State.ACCEPTED).copy(id = "other-message-123456", text = "Other work")
            history.value = history.value.copy(replica = MessageReplica(1, 2, mapOf(id to row(MessageProtocol.State.QUEUED), other.id to other)))
        }
        assertTrue(contrast(pixels()) == pendingContrast)
        val start = SystemClock.elapsedRealtime()
        compose.runOnIdle {
            history.value = history.value.copy(replica = MessageReplica(1, 3, mapOf(id to row(MessageProtocol.State.ACCEPTED))))
        }
        val accepted = pixels()
        val elapsed = SystemClock.elapsedRealtime() - start
        val acceptedContrast = contrast(accepted)
        assertTrue("Accepted text must match normal brightness", kotlin.math.abs(acceptedContrast - normalContrast) < 2)
        assertTrue("Accepted text must be brighter", pendingContrast < normalContrast * 0.75 && pendingContrast > normalContrast * 0.25)
        assertTrue("Acceptance must paint within one second", elapsed <= 1000)
        assertTrue(history.value.history.messages.none { it.role == "user" })
        save("accepted", accepted)
        screenshot("after-accepted")
        val output = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "pending-messages")
        File(output, "result.json").writeText(JSONObject().put("acceptedAfterMs", elapsed).put("pendingContrast", pendingContrast)
            .put("normalContrast", normalContrast).put("acceptedContrast", acceptedContrast).put("status", "passed").toString())
    }

    @Test fun hostAcceptanceWithTranscriptRestoresBrightness() {
        harness()
        compose.runOnIdle {
            history.value = history.value.copy(replica = MessageReplica(1, 1, mapOf(id to row(MessageProtocol.State.ACCEPTED))))
        }
        val normal = contrast(pixels())
        compose.runOnIdle {
            history.value = history.value.copy(replica = MessageReplica(1, 2, mapOf(id to row(MessageProtocol.State.QUEUED))))
        }
        val start = SystemClock.elapsedRealtime()
        compose.runOnIdle {
            val source = history.value.history.copy(rows = listOf(ChatHistoryProtocol.Row("user", "Transcript arrived", "native", "", 1, 2)))
            history.value = history.value.copy(history = source, replica = MessageReplica(1, 3, mapOf(id to row(MessageProtocol.State.ACCEPTED))))
        }
        val bitmap = pixels()
        val elapsed = SystemClock.elapsedRealtime() - start
        assertTrue(kotlin.math.abs(normal - contrast(bitmap)) < 2)
        assertTrue(elapsed <= 1000)
        save("transcript", bitmap)
        screenshot("after-transcript")
        val output = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "pending-messages")
        File(output, "transcript-result.json").writeText(JSONObject().put("acceptedAfterMs", elapsed).put("status", "passed").toString())
    }

    @After fun close() { if (::connection.isInitialized) connection.close() }
}
