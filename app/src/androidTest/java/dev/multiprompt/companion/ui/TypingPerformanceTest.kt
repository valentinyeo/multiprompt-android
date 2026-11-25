package dev.multiprompt.companion.ui

import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.Window
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.multiprompt.companion.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TypingPerformanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val counts = mutableMapOf<String, Int>()
    private val layouts = mutableMapOf<String, Int>()
    private val timings = mutableMapOf<String, Long>()
    @Volatile private var imeInsetPx = 0
    @Volatile private var activityRef: MainActivity? = null
    private var frame: ((FrameMetrics) -> Unit)? = null

    @Test fun typeAndEditOwnerScaleChat() {
        val session = requireNotNull(arguments.getString("typing_session"))
        val marker = requireNotNull(arguments.getString("typing_marker"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var output: File
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ -> frame?.invoke(metrics) }
            scenario.onActivity { activity ->
                activityRef = activity
                output = File(activity.getExternalFilesDir(null), "typing").apply { mkdirs() }
                activity.window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
                TypingProbe.compositions = { name -> counts[name] = (counts[name] ?: 0) + 1 }
                TypingProbe.layouts = { name -> layouts[name] = (layouts[name] ?: 0) + 1 }
                TypingProbe.timings = { name, ns -> timings[name] = (timings[name] ?: 0) + ns }
            }
            val records = JSONArray()
            try {
                val entry = waitNode(240_000) {
                    it.text?.toString() == "Use without account (debug)" || it.contentDescription?.toString() == "Search sessions"
                }
                if (entry.text?.toString() == "Use without account (debug)") click(entry)
                click(waitNode { it.contentDescription?.toString() == "Search sessions" })
                setText(session)
                click(waitNode(240_000) { it.contentDescription?.toString() == "Robot $session" })
                waitNode(600_000) { it.text?.contains(marker) == true }
                click(waitNode { it.isEditable })
                Debug.startMethodTracingSampling(File(output, "typing-profile").absolutePath, 8 * 1024 * 1024, 1000)
                try {
                    repeat(10) { key(5, "profile", it) { instrumentation.sendStringSync("p") } }
                } finally { Debug.stopMethodTracing() }
                val text = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefgh"
                repeat(5) { trial ->
                    setText("")
                    text.forEachIndexed { index, char ->
                        records.put(key(trial, "type", index) { instrumentation.sendStringSync(char.toString()) })
                        assertDraft(text.take(index + 1))
                    }
                    val selection = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 30)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 30)
                    }
                    assertTrue(waitNode { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection))
                    waitNode { it.isEditable && it.textSelectionStart == 30 && it.textSelectionEnd == 30 }
                    instrumentation.waitForIdleSync()
                    val edit = "MIDediting"
                    edit.forEachIndexed { index, char ->
                        records.put(key(trial, "edit", index) { instrumentation.sendStringSync(char.toString()) })
                        assertDraft(text.take(30) + edit.take(index + 1) + text.drop(30))
                    }
                    records.put(key(trial, "idle", 0) { SystemClock.sleep(100) })
                    File(output, "keys.json").writeText(records.toString(2))
                }
                val expected = text.take(30) + "MIDediting" + text.drop(30)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                click(waitNode { it.contentDescription?.toString() == "Back" })
                click(waitNode { it.contentDescription?.toString() == "Robot $session" })
                assertDraft(expected)
                File(output, "draft-reopened.txt").writeText(expected)
            } finally {
                File(output, "keys.json").writeText(records.toString(2))
                instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
                    File(output, "last-draft.txt").writeText(nodes(root).firstOrNull { it.isEditable }?.text?.toString().orEmpty())
                }
                scenario.onActivity { activity ->
                    frame = null
                    TypingProbe.compositions = null
                    TypingProbe.layouts = null
                    TypingProbe.timings = null
                    activity.window.removeOnFrameMetricsAvailableListener(listener)
                }
            }
        }
    }

    private fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        listOf(node) + (0 until node.childCount).flatMap { index -> node.getChild(index)?.let(::nodes).orEmpty() }

    private fun waitNode(timeout: Long = 30_000, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
                nodes(root).firstOrNull(predicate)?.let { return it }
            }
            SystemClock.sleep(100)
        }
        error("TYPING_NODE_MISSING after ${timeout}ms")
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        assertTrue("the resolved control must be clickable", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private fun setText(text: String) {
        val bundle = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        assertTrue(waitNode { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle))
        waitNode { it.isEditable && it.text?.toString().orEmpty() == text }
        instrumentation.waitForIdleSync()
    }

    private fun assertDraft(expected: String) {
        val field = waitNode { it.isEditable && it.text?.toString().orEmpty() == expected }
        assertEquals(expected, field.text?.toString().orEmpty())
    }

    private fun key(trial: Int, phase: String, index: Int, action: () -> Unit): JSONObject {
        val done = CountDownLatch(1)
        var cpu = 0L
        var allocated = 0L
        var start = 0L
        var result = JSONObject()
        fun capture(metrics: FrameMetrics?) {
            val cpuMs = (Debug.threadCpuTimeNanos() - cpu) / 1_000_000.0
            val bytes = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong() - allocated
            result = JSONObject().put("trial", trial).put("phase", phase).put("index", index)
                .put("main_ms", cpuMs).put("wall_ms", (System.nanoTime() - start) / 1_000_000.0)
                .put("allocated_bytes", bytes).put("compositions", JSONObject(counts.toMap()))
                .put("cpu_ns", JSONObject(timings.toMap()))
                .put("frames_ms", JSONArray().apply { metrics?.let { put(it.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0) } })
            frame = null
            done.countDown()
        }
        instrumentation.runOnMainSync {
            counts.clear()
            layouts.clear()
            timings.clear()
            imeInsetPx = (activityRef?.window?.decorView?.rootWindowInsets
                ?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0)
            allocated = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()
            cpu = Debug.threadCpuTimeNanos()
            start = System.nanoTime()
            val sequence = TypingProbe.edits
            if (phase != "idle") frame = { metrics ->
                if (metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) >= start &&
                    TypingProbe.edits > sequence && (counts["Composer"] ?: 0) > 0
                ) capture(metrics)
            }
        }
        action()
        if (phase == "idle") instrumentation.runOnMainSync { capture(null) }
        assertTrue("each key must reach a real frame", done.await(10, TimeUnit.SECONDS))
        return result
    }
}
