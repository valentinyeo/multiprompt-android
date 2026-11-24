package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.multiprompt.companion.reader.ChatHistoryMessage
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatListRemeasureTest {
    @get:Rule val compose = createComposeRule()

    private val messages = mutableStateOf((1..120).map(::reply))
    private val older = mutableStateOf(false)
    private var remeasures = 0
    private val observer: ObserverHandle = Snapshot.registerApplyObserver { changed, _ ->
        if (changed.any { (it as? State<*>)?.value is LazyListLayoutInfo }) remeasures++
    }

    @After
    fun stopObserving() {
        observer.dispose()
    }

    private fun reply(index: Int) = ChatHistoryMessage("reply-$index", if (index % 2 == 0) "user" else "assistant",
        "Reply $index", "", index.toLong(), index.toLong())

    private fun render() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.height(480.dp)) {
                    ChatHistoryList(messages.value, Modifier.height(480.dp), listState = rememberLazyListState(),
                        hasOlder = older.value)
                }
            }
        }
        settle()
    }

    private fun settle() {
        compose.waitForIdle()
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun report(reading: String, value: Int) {
        val line = "CHAT_LIST_REMEASURES $reading=$value"
        println(line)
        runCatching {
            File(System.getProperty("user.dir"), "build").mkdirs()
            File(File(System.getProperty("user.dir"), "build"), "chat-list-remeasures.txt").appendText("$line\n")
        }
    }

    @Test
    fun aRecompositionWithoutANewReplyDoesNotRemeasureTheChatList() {
        render()
        val before = remeasures
        repeat(5) { round ->
            compose.runOnIdle { older.value = round % 2 == 0 }
            settle()
        }
        val idle = remeasures - before
        report("idle", idle)
        assertEquals("a recomposition that brings no new reply remeasured the chat list", 0, idle)
    }

    @Test
    fun eachIncomingReplyRemeasuresTheChatList() {
        render()
        val before = remeasures
        repeat(5) { round ->
            compose.runOnIdle { messages.value = messages.value + reply(121 + round) }
            settle()
        }
        val live = remeasures - before
        report("live", live)
        assertTrue("five incoming replies were not observed at all, so the probe is not watching the chat list", live >= 5)
    }
}
