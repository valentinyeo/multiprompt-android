package dev.multiprompt.companion.ui

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.test.platform.app.InstrumentationRegistry
import dev.multiprompt.companion.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class TypingPerformanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val counts = mutableMapOf<String, Int>()
    private val timings = mutableMapOf<String, Long>()
    private val frames = mutableListOf<Double>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()

    @Test fun typeAndEditOwnerScaleChat() {
        val session = requireNotNull(arguments.getString("typing_session"))
        val marker = requireNotNull(arguments.getString("typing_marker"))
        val output = File(compose.activity.getExternalFilesDir(null), "typing").apply { mkdirs() }
        compose.waitUntil(240_000) {
            compose.onAllNodesWithContentDescription("Search sessions").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Search sessions").performClick()
        compose.onNode(hasSetTextAction()).performTextInput(session)
        compose.waitUntil(240_000) {
            compose.onAllNodesWithContentDescription("Robot $session").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Robot $session").performScrollTo().performClick()
        compose.waitUntil(600_000) {
            compose.onAllNodesWithText(marker, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        val field = compose.onNode(hasSetTextAction())
        field.performClick()
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frames += metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0
        }
        compose.runOnIdle {
            compose.activity.window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
            TypingProbe.compositions = { name -> counts[name] = (counts[name] ?: 0) + 1 }
            TypingProbe.timings = { name, ns -> timings[name] = (timings[name] ?: 0) + ns }
        }
        val records = JSONArray()
        val text = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefgh"
        try {
            repeat(5) { trial ->
                field.performTextClearance()
                text.forEachIndexed { index, char ->
                    records.put(key(trial, "type", index) { field.performTextInput(char.toString()) })
                }
                field.assertTextEquals(text)
                field.performTextInputSelection(TextRange(30))
                val edit = "MIDediting"
                edit.forEachIndexed { index, char ->
                    records.put(key(trial, "edit", index) { field.performTextInput(char.toString()) })
                }
                field.assertTextEquals(text.take(30) + edit + text.drop(30))
                records.put(key(trial, "idle", 0) { SystemClock.sleep(100) })
                File(output, "keys.json").writeText(records.toString(2))
            }
            val expected = text.take(30) + "MIDediting" + text.drop(30)
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithContentDescription("Robot $session").performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).assertTextEquals(expected)
            File(output, "draft-reopened.txt").writeText(expected)
            if (arguments.getString("assert_isolated") == "true") {
                val listCounts = (0 until records.length()).map { records.getJSONObject(it) }
                    .filter { it.getString("phase") != "idle" && it.getInt("index") > 0 }
                    .sumOf { it.getJSONObject("compositions").optInt("ChatHistoryList") }
                assertTrue("Typing recomposed the message list $listCounts times", listCounts == 0)
            }
            Debug.startMethodTracingSampling(File(output, "typing-profile").absolutePath, 8 * 1024 * 1024, 1000)
            try { repeat(10) { field.performTextInput("p") } } finally { Debug.stopMethodTracing() }
        } finally {
            compose.runOnIdle {
                TypingProbe.compositions = null
                TypingProbe.timings = null
                compose.activity.window.removeOnFrameMetricsAvailableListener(listener)
            }
        }
    }

    private fun key(trial: Int, phase: String, index: Int, action: () -> Unit): JSONObject {
        var cpu = 0L
        var allocated = 0L
        compose.runOnIdle {
            counts.clear()
            timings.clear()
            frames.clear()
            allocated = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()
            cpu = Debug.threadCpuTimeNanos()
        }
        val start = SystemClock.elapsedRealtimeNanos()
        action()
        compose.waitForIdle()
        return compose.runOnIdle {
            JSONObject().put("trial", trial).put("phase", phase).put("index", index)
                .put("main_ms", (Debug.threadCpuTimeNanos() - cpu) / 1_000_000.0)
                .put("wall_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0)
                .put("allocated_bytes", Debug.getRuntimeStat("art.gc.bytes-allocated").toLong() - allocated)
                .put("compositions", JSONObject(counts.toMap()))
                .put("cpu_ns", JSONObject(timings.toMap()))
                .put("frames_ms", JSONArray(frames.toList()))
        }
    }
}
