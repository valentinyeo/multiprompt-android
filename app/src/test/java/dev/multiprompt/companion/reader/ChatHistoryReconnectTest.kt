package dev.multiprompt.companion.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import dev.multiprompt.companion.ui.ChatHistoryList
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatHistoryReconnectTest {
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(scheduler))
    @get:Rule val temporary = TemporaryFolder()

    private fun text(index: Int): String = when (index) {
        0 -> "Before reconnect: history stays here"
        205 -> "After large result: caught up completely"
        else -> "Recovered post $index of 204"
    }

    private fun event(index: Int): String {
        val role = if (index % 2 == 0) "user" else "assistant"
        return JSONObject().put("type", role).put("uuid", "synthetic-$index")
            .put("timestamp", if (index == 0) "2026-10-26T16:49:00Z" else "2026-10-26T22:28:00Z")
            .put("message", JSONObject().put("role", role).put("id", "message-$index")
                .put("content", text(index))).toString() + "\n"
    }

    @Test
    fun reconnectThroughCacheKeepsAllPostsAndShowsRecoveredHistory() {
        val home = temporary.newFolder("home")
        val transcript = File(home, ".claude/projects/-synthetic/synthetic.jsonl").apply {
            parentFile!!.mkdirs()
            writeText(event(0))
        }
        val cache = ChatHistoryCache(temporary.newFolder("cache"))
        ChatHistoryProcess("synthetic", "/synthetic", home).use { client ->
            cache.save("synthetic-session", client.all())
        }
        val oversized = JSONObject().put("type", "user").put("message", JSONObject().put("role", "user")
            .put("content", JSONArray().put(JSONObject().put("type", "tool_result")
                .put("tool_use_id", "synthetic-tool").put("content", ""))))
        val body = oversized.getJSONObject("message").getJSONArray("content").getJSONObject(0)
        body.put("content", "x".repeat(595494 - (oversized.toString() + "\n").toByteArray().size))
        val record = oversized.toString() + "\n"
        assertEquals(595494, record.toByteArray().size)
        transcript.appendText(record + (1..205).joinToString("") { event(it) })
        val restored = requireNotNull(cache.restore("synthetic-session"))
        val history = ChatHistoryProcess("synthetic", "/synthetic", home, initial = restored).use { client ->
            client.poll()
            while (client.history.more) client.poll()
            client.history
        }
        assertEquals((0..205).map { "message-$it" }, history.messages.map { it.requestId })
        assertEquals((0..205).map(::text), history.messages.map { it.text })
        assertEquals(transcript.length(), history.offset)
        assertEquals(0L, history.start)
        assertEquals(206, history.messages.map { it.key }.toSet().size)
        val disconnected = ChatHistoryProtocol.Page(history.sid, history.offset + 10, history.offset + 20,
            history.inode, false, history.mtime, history.probe, "delta", history.start, emptyList())
        assertThrows(IllegalArgumentException::class.java) { history.apply(disconnected) }
        assertThrows(IllegalArgumentException::class.java) {
            history.apply(disconnected.copy(mode = "older", base = 0, end = 10, oldest = 0))
        }
        compose.setContent {
            MaterialTheme {
                ChatHistoryList(history.messages, sunlight = true, modifier = Modifier.background(Color.White))
            }
        }
        for (index in listOf(205, 103, 0)) {
            compose.onNodeWithTag("chat-history").performScrollToIndex(205 - index)
            compose.onNodeWithText(text(index)).assertIsDisplayed()
            screenshot("history-$index")
        }
    }

    private fun screenshot(name: String) {
        val node = compose.onRoot()
        val view = (checkNotNull(node.fetchSemanticsNode().root) as ViewRootForTest).view.rootView
        val attachInfo = ReflectionHelpers.getField<Any>(view, "mAttachInfo")
        val accelerated = view.isHardwareAccelerated
        ReflectionHelpers.setField(attachInfo, "mHardwareAccelerated", false)
        val redraw = CoroutineScope(UnconfinedTestDispatcher(scheduler)).launch {
            while (isActive) {
                delay(16)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
                view.viewTreeObserver.dispatchOnDraw()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
        val bitmap = try {
            node.captureToImage().asAndroidBitmap()
        } finally {
            redraw.cancel()
            ReflectionHelpers.setField(attachInfo, "mHardwareAccelerated", accelerated)
        }
        val output = File("build/reports/igsh-1045/$name.png")
        checkNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
