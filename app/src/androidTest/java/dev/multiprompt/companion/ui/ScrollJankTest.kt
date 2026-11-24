package dev.multiprompt.companion.ui

import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.multiprompt.companion.MainActivity
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScrollJankTest {
    @get:Rule(order = 0) val notifications = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private val packageName: String
        get() = InstrumentationRegistry.getInstrumentation().targetContext.packageName

    @Test fun chatListKeepsUpWithAScroll() = measure("chat", FadeScreen.SESSION)

    @Test fun inboxListKeepsUpWithAScroll() = measure("inbox", FadeScreen.INBOX_LIST)

    private fun measure(name: String, screen: FadeScreen) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent { FadeTestScreen(screen, material3 = false, sunlight = false) }
        }
        compose.waitForIdle()
        shell("dumpsys gfxinfo $packageName reset")
        val started = System.nanoTime()
        repeat(swipes) {
            compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp() }
            compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() }
        }
        compose.waitForIdle()
        val scrollMs = (System.nanoTime() - started) / 1_000_000
        val sample = FrameSample.parse(shell("dumpsys gfxinfo $packageName")).let {
            JSONObject()
                .put("screen", name)
                .put("swipes", swipes * 2)
                .put("scrollMs", scrollMs)
                .put("totalFrames", it.totalFrames)
                .put("jankyFrames", it.jankyFrames)
                .put("jankyPercent", it.jankyPercent)
                .put("p50Ms", it.p50)
                .put("p90Ms", it.p90)
                .put("p95Ms", it.p95)
                .put("p99Ms", it.p99)
        }
        assertTrue("$name rendered no scroll frames: $sample", sample.getInt("totalFrames") >= 60)
        val directory = File(
            checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
            "scroll-jank",
        ).apply { mkdirs() }
        File(directory, "$name.json").writeText(sample.toString())
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() }
    }

    private companion object {
        const val swipes = 6
    }
}

internal data class FrameSample(
    val totalFrames: Int,
    val jankyFrames: Int,
    val jankyPercent: Double,
    val p50: Int,
    val p90: Int,
    val p95: Int,
    val p99: Int,
) {
    companion object {
        private val total = Regex("^Total frames rendered: (\\d+)$", RegexOption.MULTILINE)
        private val janky = Regex("^Janky frames: (\\d+) \\((\\d+(?:\\.\\d+)?)%\\)$", RegexOption.MULTILINE)

        fun parse(dump: String): FrameSample = FrameSample(
            totalFrames = total.find(dump)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            jankyFrames = janky.find(dump)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            jankyPercent = janky.find(dump)?.groupValues?.get(2)?.toDoubleOrNull() ?: 0.0,
            p50 = percentile(dump, "50th"),
            p90 = percentile(dump, "90th"),
            p95 = percentile(dump, "95th"),
            p99 = percentile(dump, "99th"),
        )

        private fun percentile(dump: String, label: String): Int =
            Regex("^$label percentile: (\\d+)ms$", RegexOption.MULTILINE).find(dump)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}
