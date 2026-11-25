package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.multiprompt.companion.reader.ChatHistory
import dev.multiprompt.companion.reader.ChatHistoryProtocol
import dev.multiprompt.companion.reader.LongChatSnapshot
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatListCostTest {
    @get:Rule val compose = createComposeRule()
    private val listState = LazyListState()
    private val history = mutableStateOf(ChatHistory(), neverEqualPolicy())
    private var composed = 0
    private var projectNanos = 0L

    @Before
    fun countProductionRows() {
        Composer.setTracer(object : CompositionTracer {
            override fun isTraceInProgress() = true
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
                if (info.contains(".HistoryBubble (")) composed++
            }
            override fun traceEventEnd() = Unit
        })
    }

    @After
    fun stopCounting() {
        Composer.setTracer(null)
    }

    private fun measures(): Int = LazyListState::class.java.getDeclaredField("numMeasurePasses").let {
        it.isAccessible = true
        it.getInt(listState)
    }

    private fun settle() {
        compose.waitForIdle()
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    @Test
    fun measuresTheComposeCostOfEachLongChatSnapshot() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.height(520.dp)) {
                    val snapshot = history.value
                    val messages = remember(snapshot) {
                        val start = System.nanoTime()
                        val projected = snapshot.projectMessages(now = NOW, sent = null)
                        projectNanos += System.nanoTime() - start
                        projected
                    }
                    ChatHistoryList(messages, listState = listState)
                }
            }
        }
        settle()
        val table = ArrayList<String>()
        for (size in SIZES) {
            for (growing in listOf(false, true)) table.add(measure(size, growing))
        }
        table.forEach { println(it) }
    }

    private fun prepared(targetBytes: Int, growing: Boolean): List<ChatHistory> {
        var current = ChatHistory()
        return (0 until SNAPSHOTS).map { snapshot ->
            val id = snapshot + 1L
            val built = LongChatSnapshot.built(id, targetBytes, if (growing) snapshot else 0)
            val page = requireNotNull(ChatHistoryProtocol.parse(built.reply, id, LongChatSnapshot.identity))
            current = current.apply(page)
            current
        }
    }

    private fun measure(targetBytes: Int, growing: Boolean): String {
        val size = "${targetBytes / 1024}KB"
        val mode = if (growing) "growing" else "identical"
        val sessions = prepared(targetBytes, growing)
        val passes = LongArray(SNAPSHOTS)
        val projections = LongArray(SNAPSHOTS)
        val rows = IntArray(SNAPSHOTS)
        compose.runOnIdle { history.value = sessions.first() }
        settle()
        repeat(SNAPSHOTS) { snapshot ->
            val beforeComposed = composed
            val beforeProject = projectNanos
            val beforeMeasures = measures()
            val start = System.nanoTime()
            compose.runOnIdle { history.value = sessions[snapshot] }
            settle()
            passes[snapshot] = System.nanoTime() - start
            projections[snapshot] = projectNanos - beforeProject
            rows[snapshot] = composed - beforeComposed
            assertTrue("the chat list must measure each snapshot", measures() >= beforeMeasures)
        }
        if (!growing) assertEquals("identical snapshots must not recompose a chat row", 0, rows.sum())
        val projected = sessions.last().projectMessages(now = NOW, sent = null)
        assertTrue("the long chat must project many messages", projected.size > 100)
        report("size=$size mode=$mode snapshots=$SNAPSHOTS messages=${projected.size}", passes, projections, rows)
        return "MUBO453_TABLE size=$size mode=$mode messages=${projected.size}" +
            " compose=${median(passes)} project_on_main=${median(projections)} rows=${rows.sum()}" +
            " remeasures=${measures()}"
    }

    private fun report(prefix: String, passes: LongArray, projections: LongArray, rows: IntArray) {
        val composeLine = "MUBO453_COST stage=compose thread=Main" +
            " mean_ms=${millis(passes.average().toLong())} median_ms=${median(passes)} p95_ms=${percentile(passes)}" +
            " rows=${rows.sum()} site=ChatHistoryList.kt:236"
        val projectLine = "MUBO453_COST stage=project_on_main thread=Main" +
            " mean_ms=${millis(projections.average().toLong())} median_ms=${median(projections)} p95_ms=${percentile(projections)}" +
            " site=ChatHistoryList.kt:122 remember(history) -> ChatHistory.kt:67"
        for (line in listOf(composeLine, projectLine)) println("$prefix $line")
        runCatching {
            val output = File(File(System.getProperty("user.dir"), "build"), "mubo-453")
            output.mkdirs()
            File(output, "chat-list.txt").appendText("$prefix\n$composeLine\n$projectLine\n")
        }
    }

    private fun median(values: LongArray): String = millis(sorted(values)[values.size / 2])

    private fun percentile(values: LongArray): String = millis(sorted(values)[((values.size - 1) * 95) / 100])

    private fun sorted(values: LongArray): LongArray = values.sortedArray()

    private fun millis(nanos: Long): String = String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0)

    private companion object {
        const val SNAPSHOTS = 50
        const val NOW = 1_700_100_000.0
        val SIZES = listOf(95 * 1024, 500 * 1024)
    }
}
