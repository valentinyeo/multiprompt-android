package dev.multiprompt.companion.ui

import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.core.view.WindowCompat
import dev.multiprompt.companion.MainActivity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TopFadeInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun inboxScrollsUnderTheStatusBarFade() = verify(FadeScreen.INBOX_LIST, "inbox", "Session 0")

    @Test fun chatScrollsUnderTheStatusBarFade() = verify(FadeScreen.SESSION, "chat", "Message 0", chat = true)

    @Suppress("DEPRECATION")
    private fun verify(screen: FadeScreen, name: String, first: String, chat: Boolean = false) {
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
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val target = File(compose.activity.getExternalFilesDir(null), "$name.png")
        target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
