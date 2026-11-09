package dev.multiprompt.companion.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
import kotlinx.coroutines.flow.first
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
        "attempt", state, null, null, 1, System.currentTimeMillis() / 1000.0)

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

    private fun bubbleHeight(key: String) = compose.onNodeWithTag("chat-message-$key").getUnclippedBoundsInRoot().let { it.bottom.value - it.top.value }

    private fun assertOnlyBubbles() {
        listOf("Queued", "Sending", "Accepted", "Failed", "Not sent", "Confirmation unknown", "Check status", "Check reset", "Send again", "Remove").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }

    private fun assertNotSent(count: Int) {
        assertEquals(count, compose.onAllNodesWithText("Not sent").fetchSemanticsNodes().size)
        assertEquals(count, compose.onAllNodesWithText("Send again").fetchSemanticsNodes().size)
        assertEquals(count, compose.onAllNodesWithText("Remove").fetchSemanticsNodes().size)
        listOf("Queued", "Sending", "Accepted", "Failed", "Confirmation unknown", "Check status", "Check reset").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }

    @Test fun aSentBubbleIsOnlyItsText() {
        harness()
        compose.onNodeWithText("Synthetic prompt").assertIsDisplayed()
        assertOnlyBubbles()
        val queued = bubbleHeight("@shared:$id")
        compose.runOnIdle {
            history.value = history.value.copy(replica = MessageReplica(1, 2, mapOf(id to row(MessageProtocol.State.ACCEPTED))))
        }
        compose.onNodeWithText("Synthetic prompt").assertIsDisplayed()
        assertOnlyBubbles()
        assertEquals("acceptance must not move the bubble", queued, bubbleHeight("@shared:$id"), 0.5f)
    }

    @Test fun aFailedSendSaysNotSentAndAnUnknownOneStaysCalm() {
        harness()
        compose.runOnIdle {
            history.value = history.value.copy(replica = MessageReplica(1, 2, mapOf(id to row(MessageProtocol.State.FAILED))))
        }
        compose.onNodeWithText("Synthetic prompt").assertIsDisplayed()
        assertNotSent(1)
        compose.runOnIdle {
            history.value = history.value.copy(replica = MessageReplica(1, 3, emptyMap()))
        }
        compose.runOnIdle {
            val entry = MessageJournal.Entry("unknown123456789012", "account", MessageProtocol.Scope("claude", "proof", "pending-proof"),
                "submit", "othermessage123456", "Second attempt", 1, "attempt", true)
            history.value = history.value.copy(unresolved = listOf(entry))
        }
        compose.onNodeWithText("Second attempt").assertIsDisplayed()
        assertOnlyBubbles()
    }

    @Test fun threeSentBubblesKeepOnlyTheirText() {
        harness()
        val first = row(MessageProtocol.State.QUEUED).copy(id = "queued1234567890123", text = "First calm message")
        val second = row(MessageProtocol.State.ACCEPTED).copy(id = "accepted12345678901", text = "Second calm message")
        val third = row(MessageProtocol.State.FAILED).copy(id = "failed123456789012", text = "Third calm message")
        compose.runOnIdle {
            history.value = history.value.copy(replica = MessageReplica(1, 2, mapOf(first.id to first, second.id to second, third.id to third)))
        }
        listOf("First calm message", "Second calm message", "Third calm message").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        screenshot("calm-sent-bubbles")
        assertNotSent(1)
    }

    @Test fun anAnsweredSendShowsTheReplyBelowIt() {
        harness()
        val older = (1L..15L).map { position ->
            row(MessageProtocol.State.ACCEPTED).copy(id = "older%013d".format(position), text = "Older synthetic prompt $position",
                position = position, created = 1793690000.0 + position * 600)
        }
        val asked = row(MessageProtocol.State.ACCEPTED).copy(position = 16, text = "Synthetic ask", created = 1793712066.72)
        compose.runOnIdle {
            history.value = ChatHistoryState(ChatHistory(sid = "proof", inode = 1, offset = 900, start = 100, more = true, rows = listOf(
                ChatHistoryProtocol.Row("user", "Synthetic ask", "u1", "2026-11-03T13:21:09.775Z", 100, 300),
                ChatHistoryProtocol.Row("assistant", "Synthetic answer arrived", "a1", "2026-11-03T13:21:26.722Z", 600, 900),
            )), loaded = true, replica = MessageReplica(1, 2, (older + asked).associateBy { it.id }))
        }
        compose.onNodeWithText("Synthetic answer arrived").assertIsDisplayed()
        assertEquals(1, compose.onAllNodesWithText("Synthetic ask").fetchSemanticsNodes().size)
        val ask = compose.onNodeWithText("Synthetic ask").getUnclippedBoundsInRoot()
        val answer = compose.onNodeWithText("Synthetic answer arrived").getUnclippedBoundsInRoot()
        assertTrue("the reply must sit below the sent message", answer.top.value >= ask.bottom.value)
        screenshot("answered-send")
    }

    @Test fun aLeftoverRetryShowsNoButtons() {
        harness()
        compose.runOnIdle {
            val entry = MessageJournal.Entry("retry123456789012", "account", MessageProtocol.Scope("claude", "proof", "pending-proof"),
                "retry", id, null, 1, "attempt", true)
            history.value = history.value.copy(replica = MessageReplica(1, 2, mapOf(id to row(MessageProtocol.State.CANCELLED))), unresolved = listOf(entry))
        }
        compose.onNodeWithText("Synthetic prompt").assertDoesNotExist()
        assertOnlyBubbles()
    }

    @Test fun aLostResetRecoversWithoutAButton() {
        val f = ChatHistoryActorTest().Fixture("claude")
        f.dropReset = true
        val current = f.connection()
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MultipromptApplication
        try {
            runBlocking {
                current.select(true); current.start(); f.ready(current)
                assertTrue(withTimeout(10_000) { current.resetConversation().await() } == dev.multiprompt.companion.reader.MessageActionResult.Unknown)
            }
            assertEquals("reset", f.journal.entries().single().operation)
            f.dropReset = false
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
            assertOnlyBubbles()
            compose.waitUntil(20_000) { f.journal.entries().isEmpty() }
            runBlocking {
                assertTrue(withTimeout(10_000) { current.submit("after reset") } is dev.multiprompt.companion.reader.PromptAdmission.Recorded)
            }
            assertOnlyBubbles()
        } finally {
            current.close()
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
            assertTrue(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun screenshot(name: String) {
        val output = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "pending-messages")
        output.mkdirs()
        File(output, "$name.png").outputStream().use {
            assertTrue(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
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
