package dev.multiprompt.companion.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.multiprompt.companion.MainActivity
import dev.multiprompt.companion.data.SessionReadStore
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class DayThemeTest {
    @get:Rule(order = 0) val notifications = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val problems = mutableListOf<String>()
    private val report = mutableListOf<String>()
    private val output: File get() = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "day-theme")

    @Test fun fiveScreensFollowTheThemeWithReadableText() {
        for (sunlight in listOf(false, true)) {
            val theme = if (sunlight) "light" else "dark"
            SessionReadStore(InstrumentationRegistry.getInstrumentation().targetContext)
                .setAppTheme(if (sunlight) AppTheme.SUNLIGHT else AppTheme.DARK)
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Sign in").assertIsDisplayed()
            captureAndCheck("$theme-sign-in", sunlight)
            compose.activityRule.scenario.onActivity { activity ->
                val lightIcons = WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars
                if (lightIcons != sunlight) problems += "$theme status icons do not follow the page"
                activity.setContent { FadeTestScreen(FadeScreen.INBOX_LIST, material3 = false, sunlight = sunlight) }
            }
            compose.onNodeWithText("Inbox").assertIsDisplayed()
            captureAndCheck("$theme-inbox", sunlight)
            compose.onNodeWithContentDescription("Session list actions").performClick()
            compose.onNodeWithText("Settings").assertIsDisplayed()
            captureAndCheck("$theme-menu", sunlight)
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Session list settings").assertIsDisplayed()
            captureAndCheck("$theme-settings", sunlight)
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent { FadeTestScreen(FadeScreen.SESSION, material3 = false, sunlight = sunlight) }
            }
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(39)
            compose.onNodeWithText("Message 0", substring = true).assertIsDisplayed()
            captureAndCheck("$theme-chat", sunlight)
            if (sunlight) checkReaderRoles()
        }
        output.mkdirs()
        File(output, "contrast.txt").writeText(report.joinToString("\n") + "\n" + problems.joinToString("\n"))
        assertTrue("DAY_THEME_CONTRAST: ${problems.joinToString("; ")}", problems.isEmpty())
    }

    private fun captureAndCheck(name: String, sunlight: Boolean) {
        compose.waitForIdle()
        output.mkdirs()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.takeScreenshot().let { bitmap ->
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        if (!sunlight) return
        val textNodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        var checked = 0
        textNodes.fetchSemanticsNodes().forEachIndexed { index, node ->
            val layouts = mutableListOf<TextLayoutResult>()
            val interaction = textNodes[index]
            if (node.boundsInRoot.width <= 0 || node.boundsInRoot.height <= 0) return@forEachIndexed
            interaction.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val bitmap = interaction.captureToImage().asAndroidBitmap()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val background = Color(pixels.asSequence().groupingBy { it }.eachCount().maxBy { it.value }.key)
            bitmap.recycle()
            layouts.forEach { layout ->
                val colors = listOf(layout.layoutInput.style.color) + layout.layoutInput.text.spanStyles.map { it.item.color }
                colors.filter { it != Color.Unspecified }.forEach { foreground ->
                    val ink = foreground.compositeOver(background)
                    val ratio = (max(ink.luminance(), background.luminance()) + 0.05f) / (min(ink.luminance(), background.luminance()) + 0.05f)
                    val label = "$name '${layout.layoutInput.text.text.take(60)}' contrast=$ratio"
                    report += label
                    if (ratio < 4.5f) problems += label
                    checked++
                }
            }
        }
        assertTrue("$name must measure rendered text", checked > 0)
        report += "$name checked=$checked"
    }

    private fun checkReaderRoles() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MultipromptTheme(sunlight = true, material3 = false, readerStyle = true) {
                    val scheme = MaterialTheme.colorScheme
                    val pairs = listOf(
                        "user" to (chatTextColor(true) to chatBubbleColor(true, true)),
                        "assistant" to (chatTextColor(true) to chatBubbleColor(false, true)),
                        "hint" to (chatHintColor(true) to chatBubbleColor(false, true)),
                        "controls" to (scheme.onSurface to scheme.surfaceContainerHighest),
                        "composer" to (scheme.onSurface to scheme.surfaceContainer),
                    )
                    SideEffect {
                        pairs.forEach { (name, pair) ->
                            val ratio = (max(pair.first.luminance(), pair.second.luminance()) + 0.05f) / (min(pair.first.luminance(), pair.second.luminance()) + 0.05f)
                            report += "reader-$name contrast=$ratio"
                            if (ratio < 4.5f) problems += "reader-$name contrast=$ratio"
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
