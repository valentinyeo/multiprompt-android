package dev.multiprompt.companion.ui

import android.graphics.Rect
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.Window
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
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

class LongDraftTypingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val counts = mutableMapOf<String, Int>()
    private val layouts = mutableMapOf<String, Int>()
    private val timings = mutableMapOf<String, Long>()
    private val composerStyle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp)
    private val diagnostics = JSONObject()
    @Volatile private var imeInsetPx = 0
    @Volatile private var activityRef: MainActivity? = null
    private var frame: ((FrameMetrics) -> Unit)? = null
    private var measurer: TextMeasurer? = null
    private var fieldWidth = 0

    @Test fun typeAndEditLongDrafts() {
        val session = requireNotNull(arguments.getString("typing_session"))
        val marker = requireNotNull(arguments.getString("typing_marker"))
        val lengths = requireNotNull(arguments.getString("draft_lengths")).split(',').map { it.trim().toInt() }
        val trials = (arguments.getString("trials") ?: "5").toInt()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var output: File
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ -> frame?.invoke(metrics) }
            scenario.onActivity { activity ->
                activityRef = activity
                output = File(activity.getExternalFilesDir(null), "long-drafts").apply { mkdirs() }
                val metrics = activity.resources.displayMetrics
                measurer = TextMeasurer(
                    createFontFamilyResolver(activity),
                    Density(metrics.density, activity.resources.configuration.fontScale),
                    LayoutDirection.Ltr,
                )
                activity.window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
                TypingProbe.compositions = { name -> counts[name] = (counts[name] ?: 0) + 1 }
                TypingProbe.layouts = { name -> layouts[name] = (layouts[name] ?: 0) + 1 }
                TypingProbe.timings = { name, ns -> timings[name] = (timings[name] ?: 0) + ns }
            }
            val records = JSONArray()
            try {
                enterChat(session, marker)
                val field = waitNode { it.isEditable }
                val bounds = Rect()
                field.getBoundsInScreen(bounds)
                fieldWidth = bounds.width()
                assertTrue("the composer field must have a width to measure", fieldWidth > 0)
                for (length in lengths) {
                    val draft = Base64.decode(requireNotNull(arguments.getString("draft_$length")), Base64.NO_WRAP).decodeToString()
                    assertEquals("the draft must arrive at its exact length", length, draft.length)
                    File(output, "draft-$length.txt").writeText(draft)
                    repeat(trials) { trial ->
                        setText(draft)
                        placeCaret(draft.length, "type-$length-$trial")
                        var typed = draft
                        "abcdefghij".forEachIndexed { index, char ->
                            records.put(key(length, trial, "type", index, typed) { press(char) })
                            typed += char
                            assertDraft(typed)
                        }
                        val middle = draft.length / 2
                        var edited = typed
                        placeCaret(middle, "edit-$length-$trial")
                        "midediting".forEachIndexed { index, char ->
                            records.put(key(length, trial, "edit", index, edited) { press(char) })
                            edited = edited.substring(0, middle + index) + char + edited.substring(middle + index)
                            assertDraft(edited)
                        }
                        File(output, "keys.json").writeText(records.toString(2))
                    }
                }
            } finally {
                File(output, "keys.json").writeText(records.toString(2))
                File(output, "diagnostics.json").writeText(diagnostics.toString(2))
                instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
                    File(output, "last-draft.txt").writeText(nodes(root).firstOrNull { it.isEditable }?.text?.toString().orEmpty())
                }
                scenario.onActivity { activity ->
                    frame = null
                    TypingProbe.compositions = null
                    TypingProbe.layouts = null
                    TypingProbe.timings = null
                    measurer = null
                    activity.window.removeOnFrameMetricsAvailableListener(listener)
                }
            }
        }
    }

    private fun enterChat(session: String, marker: String) {
        val entry = waitNode(240_000) {
            it.text?.toString() == "Use without account (debug)" || it.contentDescription?.toString() == "Search sessions"
        }
        if (entry.text?.toString() == "Use without account (debug)") click(entry)
        click(waitNode { it.contentDescription?.toString() == "Search sessions" })
        setText(session)
        click(waitNode(240_000) { it.contentDescription?.toString() == "Robot $session" })
        waitNode(600_000) { it.text?.contains(marker) == true }
        click(waitNode { it.isEditable })
        hideIme()
        waitNode { it.isEditable && it.isFocused }
    }

    private fun hideIme() {
        if (imeInset() == 0) return
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (imeInset() > 0 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
    }

    private fun imeInset(): Int {
        var inset = 0
        instrumentation.runOnMainSync {
            inset = activityRef?.window?.decorView?.rootWindowInsets
                ?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
        }
        return inset
    }

    private fun press(char: Char) {
        val code = when {
            char in 'a'..'z' -> KeyEvent.KEYCODE_A + (char - 'a')
            char in '0'..'9' -> KeyEvent.KEYCODE_0 + (char - '0')
            else -> error("LONG_DRAFT_KEY_UNSUPPORTED: $char")
        }
        instrumentation.sendKeyDownUpSync(code)
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
        error("LONG_DRAFT_NODE_MISSING after ${timeout}ms")
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

    private fun placeCaret(offset: Int, label: String) {
        val selection = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, offset)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, offset)
        }
        val applied = waitNode { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
        if (applied) waitNode { it.isEditable && it.textSelectionStart == offset && it.textSelectionEnd == offset }
        instrumentation.waitForIdleSync()
        diagnostics.put(label, describe(waitNode { it.isEditable }).put("applied", applied).put("wanted", offset))
    }

    private fun describe(node: AccessibilityNodeInfo): JSONObject {
        val text = node.text?.toString().orEmpty()
        return JSONObject().put("text_chars", text.length)
            .put("selection_start", node.textSelectionStart).put("selection_end", node.textSelectionEnd)
            .put("focused", node.isFocused)
            .put("head", text.take(16)).put("tail", text.takeLast(16))
    }

    private fun assertDraft(expected: String) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        var last = ""
        while (SystemClock.uptimeMillis() < deadline) {
            last = instrumentation.uiAutomation.rootInActiveWindow
                ?.let { root -> nodes(root).firstOrNull { it.isEditable }?.text?.toString().orEmpty() } ?: ""
            if (last == expected) return
            SystemClock.sleep(100)
        }
        val difference = expected.indices.firstOrNull { index -> last.getOrNull(index) != expected[index] } ?: minOf(last.length, expected.length)
        error("LONG_DRAFT_TEXT_MISMATCH: the composer holds ${last.length} of ${expected.length} characters, first difference at $difference")
    }

    private fun key(length: Int, trial: Int, phase: String, index: Int, text: String, action: () -> Unit): JSONObject {
        val done = CountDownLatch(1)
        var cpu = 0L
        var allocated = 0L
        var start = 0L
        var result = JSONObject()
        fun capture(metrics: FrameMetrics?) {
            val cpuMs = (Debug.threadCpuTimeNanos() - cpu) / 1_000_000.0
            val bytes = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong() - allocated
            val observed = instrumentation.uiAutomation.rootInActiveWindow
                ?.let { root -> nodes(root).firstOrNull { it.isEditable } }
            result = JSONObject().put("length", length).put("trial", trial).put("phase", phase).put("index", index)
                .put("observed", observed?.let { describe(it) } ?: JSONObject())
                .put("chars", text.length)
                .put("main_ms", cpuMs).put("wall_ms", (System.nanoTime() - start) / 1_000_000.0)
                .put("allocated_bytes", bytes).put("compositions", JSONObject(counts.toMap()))
                .put("layouts", JSONObject(layouts.toMap()))
                .put("ime_inset_px", imeInsetPx)
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
            val measure = measurer
            if (measure != null) {
                TypingProbe.measure("composerTextLayout") {
                    measure.measure(text = text, style = composerStyle, maxLines = 6,
                        constraints = Constraints(maxWidth = fieldWidth))
                }
            }
            frame = { metrics ->
                if (metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) >= start &&
                    TypingProbe.edits > sequence && (counts["Composer"] ?: 0) > 0
                ) capture(metrics)
            }
        }
        action()
        assertTrue("each key must reach a real frame", done.await(20, TimeUnit.SECONDS))
        return result
    }
}
