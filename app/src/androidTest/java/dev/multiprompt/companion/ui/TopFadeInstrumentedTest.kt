package dev.multiprompt.companion.ui

import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.core.view.WindowCompat
import dev.multiprompt.companion.MainActivity
import dev.multiprompt.companion.data.SessionReadStore
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TopFadeInstrumentedTest {
    @get:Rule(order = 0) val notifications = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun inboxScrollsUnderTheStatusBarFade() = verify(FadeScreen.INBOX_LIST, "inbox", "Session 0")

    @Test fun chatScrollsUnderTheStatusBarFade() = verify(FadeScreen.SESSION, "chat", "Message 0", chat = true)

    @Test fun fadeBlendsOverContentWithoutAnOpaqueCutoff() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MultipromptTheme {
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Red)) {
                        TopFade { Spacer(Modifier.height(56.dp)) }
                    }
                }
            }
        }
        compose.waitForIdle()
        val fade = compose.onNodeWithTag("top-fade", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val blend = bitmap.getPixel(1, (fade.bottom + 12 * density).toInt())
        val below = bitmap.getPixel(1, (fade.bottom + 26 * density).toInt())
        assertTrue("Fade must blend with the content beneath it", Color.red(blend) in 17..254)
        assertEquals("Content below the fade remains untouched", Color.RED, below)
        bitmap.recycle()
    }

    @Suppress("DEPRECATION")
    private fun verify(screen: FadeScreen, name: String, first: String, chat: Boolean = false) {
        SessionReadStore(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext).setAppTheme(AppTheme.DARK)
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent { FadeTestScreen(screen, material3 = false, sunlight = false) }
        }
        val list = compose.onNode(hasScrollToIndexAction())
        if (chat) list.performScrollToIndex(39)
        compose.waitForIdle()
        screenshot("$name-top")
        val firstBounds = compose.onNodeWithText(first, substring = chat).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        list.performScrollToIndex(15)
        compose.waitForIdle()
        screenshot("$name-scrolled")
        val fade = compose.onNodeWithTag("top-fade", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val viewport = list.fetchSemanticsNode().boundsInRoot
        assertEquals("List must reach the transparent status bar", 0f, viewport.top, 1f)
        assertEquals("Fade must include the status bar", 0f, fade.top, 1f)
        assertTrue("Nothing hidden at rest", firstBounds.top >= fade.bottom - 1f)
        assertTrue("Scrolled content must pass under the top buttons", viewport.top < fade.bottom)
        compose.activityRule.scenario.onActivity { activity ->
            assertEquals(Color.TRANSPARENT, activity.window.statusBarColor)
            assertFalse(WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars)
        }
    }

    private fun screenshot(name: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        listOf(
            "mkdir -p /data/local/tmp/top-fade-proof",
            "screencap -p /data/local/tmp/top-fade-proof/$name.png",
        ).forEach { command ->
            val output = instrumentation.uiAutomation.executeShellCommand(command)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
        }
    }
}
