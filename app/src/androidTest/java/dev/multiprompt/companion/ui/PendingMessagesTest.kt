package dev.multiprompt.companion.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.test.platform.app.InstrumentationRegistry
import dev.multiprompt.companion.MultipromptApplication
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.reader.ChatHistory
import dev.multiprompt.companion.reader.QueuedMessages
import dev.multiprompt.companion.reader.ChatHistoryConnection
import dev.multiprompt.companion.reader.ChatHistoryProtocol
import dev.multiprompt.companion.reader.ChatHistoryState
import dev.multiprompt.companion.reader.TranscriptProtocol
import dev.multiprompt.companion.reader.ReaderState
import dev.multiprompt.companion.reader.ReaderStatus
import dev.multiprompt.companion.reader.SessionReaderConnection
import dev.multiprompt.companion.ssh.TmuxText
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PendingMessagesTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as MultipromptApplication
    private val prompt = "Please check the next change"
    private val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "pending-proof", "/pending")
    private lateinit var connection: SessionReaderConnection
    private lateinit var history: MutableStateFlow<ChatHistoryState>
    private lateinit var reader: MutableStateFlow<ReaderState>
    private var pendingContrast = 0.0
    private val output get() = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "pending-messages")

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(target: Any, name: String): T = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as T

    private fun setField(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun harness() {
        val host = HostProfile("pending-proof", "Proof", "", port = 0, username = "proof")
        val stream = ChatHistoryConnection(app.sshRepository, host, identity, app.chatHistoryScript, app.chatHistoryCache)
        connection = SessionReaderConnection(app.sshRepository, host, identity.tmux, identity.agent, app.chatHistoryScript, app.chatHistoryCache)
        setField(connection, "historyIdentity", identity)
        setField(connection, "historyConnection", stream)
        history = field(stream, "_state")
        reader = field(connection, "_state")
        reader.value = ReaderState(status = ReaderStatus.Live, screenOutput = "✻ Thinking… (esc to interrupt)",
            hookState = TmuxText.HookState(true, System.currentTimeMillis() / 1000 - 1))
        history.value = ChatHistoryState(ChatHistory("proof", 1, 100, rows = listOf(
            ChatHistoryProtocol.Row("user", prompt, "normal", "", 0, 100),
        )), loaded = true)
        compose.setContent {
            MultipromptTheme(readerStyle = true) {
                val state by connection.state.collectAsState()
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding()) {
                        Text("Pending messages · Claude")
                        ChatHistoryView(connection, state, identity.agent, identity.cwd, 1f, false,
                            ReplySwipeGate(), { _, _ -> }, {}, rememberLazyListState(), 0, "Claude", null, {},
                            modifier = Modifier.weight(1f))
                        Button(onClick = { assertTrue(connection.sendPrompt(prompt)) }) { Text("Send while busy") }
                    }
                }
            }
        }
    }

    private fun pixels() = compose.onNodeWithText(prompt).captureToImage().asAndroidBitmap()

    private fun contrast(bitmap: Bitmap): Double {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val luminance = pixels.map { (Color.red(it) * 0.2126 + Color.green(it) * 0.7152 + Color.blue(it) * 0.0722) }
        return luminance.max() - luminance.min()
    }

    private fun screenshot(name: String) {
        output.mkdirs()
        File(output, "$name.png").outputStream().use {
            assertTrue(instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun savePixels(name: String, bitmap: Bitmap) {
        output.mkdirs()
        File(output, "$name-text.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun send(): Double {
        val normalPixels = pixels()
        savePixels("normal", normalPixels)
        val normal = contrast(normalPixels)
        compose.runOnIdle {
            history.value = history.value.copy(history = ChatHistory("proof", 1, 100, rows = listOf(
                ChatHistoryProtocol.Row("assistant", "Working on the previous request", "previous", "", 0, 100),
            )))
        }
        compose.onNodeWithText("Send while busy").performClick()
        compose.runOnIdle {
            reader.value = reader.value.copy(sentRecord = reader.value.sentRecord.map {
                it.copy(delivery = QueuedMessages.Delivery.QUEUED, sid = "proof", inode = 1, afterOffset = 100)
            })
        }
        val pendingPixels = pixels()
        savePixels("pending", pendingPixels)
        val pending = contrast(pendingPixels)
        pendingContrast = pending
        screenshot("before-pending")
        assertTrue("Waiting bubble must be half opacity: pending=$pending normal=$normal", pending < normal * 0.75 && pending > normal * 0.25)
        return normal
    }

    private fun applyRow(row: JSONObject) {
        val current = history.value.history
        val wire = "@mp\t1\thistory\tok\tproof\t${current.offset}\t${current.offset + 100}\t1\t0\tclaude\tpending-proof\t1\t${"a".repeat(64)}\tdelta\t0\n" +
            row.put("offset", current.offset).put("end", current.offset + 100).toString() + "\n${TranscriptProtocol.END}\n"
        val reply = JSONObject().put("id", 1).put("wire", wire).toString()
        history.value = history.value.copy(history = current.apply(checkNotNull(ChatHistoryProtocol.parse(reply, 1, identity))))
    }

    @After fun close() {
        if (::connection.isInitialized) connection.close()
    }

    @Test fun hostReceiptRestoresBrightnessWithinOneSecondBeforeUserTranscript() {
        harness()
        val normal = send()
        compose.runOnIdle {
            applyRow(JSONObject().put("role", "assistant").put("text", "").put("timestamp", Instant.now().toString())
                .put("settlement", JSONObject().put("operation", "remove").put("content", "An unrelated prompt")))
        }
        assertTrue("Unrelated work must not accept this prompt", contrast(pixels()) < normal * 0.75)
        var acceptedAt = 0L
        compose.runOnIdle {
            acceptedAt = SystemClock.elapsedRealtime()
            val token = reader.value.sentRecord.single().messageId
            applyRow(JSONObject().put("role", "status").put("deliveries", org.json.JSONArray()
                .put(JSONObject().put("id", token).put("state", "delivered"))))
        }
        val acceptedPixels = pixels()
        val accepted = contrast(acceptedPixels)
        val elapsed = SystemClock.elapsedRealtime() - acceptedAt
        assertTrue("Exact receipt must restore normal pixels: $accepted vs $normal", kotlin.math.abs(accepted - normal) < 2)
        assertTrue("Acceptance must paint within one second: ${elapsed}ms", elapsed <= 1000)
        assertTrue(history.value.history.messages.none { it.role == "user" })
        savePixels("accepted", acceptedPixels)
        screenshot("after-accepted")
        File(output, "result.json").writeText(JSONObject().put("acceptedAfterMs", elapsed).put("pendingContrast", pendingContrast)
            .put("normalContrast", normal).put("acceptedContrast", accepted).put("status", "passed").toString())
    }

    @Test fun hostReceiptAndTranscriptRestoreBrightness() {
        harness()
        val normal = send()
        var acceptedAt = 0L
        compose.runOnIdle {
            acceptedAt = SystemClock.elapsedRealtime()
            val token = reader.value.sentRecord.single().messageId
            applyRow(JSONObject().put("role", "status").put("deliveries", org.json.JSONArray()
                .put(JSONObject().put("id", token).put("state", "delivered"))))
            applyRow(JSONObject().put("role", "user").put("text", prompt).put("id", "accepted")
                .put("timestamp", Instant.now().toString()))
        }
        val acceptedPixels = pixels()
        val elapsed = SystemClock.elapsedRealtime() - acceptedAt
        assertEquals(normal, contrast(acceptedPixels), 2.0)
        assertTrue("Transcript acceptance must paint within one second: ${elapsed}ms", elapsed <= 1000)
        savePixels("transcript", acceptedPixels)
        screenshot("after-transcript")
        File(output, "transcript-result.json").writeText(JSONObject().put("acceptedAfterMs", elapsed)
            .put("status", "passed").toString())
    }
}
