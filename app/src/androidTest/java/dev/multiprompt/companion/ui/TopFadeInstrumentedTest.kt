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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.core.view.WindowCompat
import dev.multiprompt.companion.MainActivity
import dev.multiprompt.companion.MainViewModel
import androidx.lifecycle.ViewModelProvider
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

    @Test fun fullAppInboxMatchesChatFadeInDarkTheme() = verifyAppInbox(false)

    @Test fun fullAppInboxMatchesChatFadeInLightTheme() = verifyAppInbox(true)

    private fun verifyAppInbox(sunlight: Boolean) {
        val theme = if (sunlight) "light" else "dark"
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent { AppInboxFixture(sunlight) }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(15)
        compose.waitForIdle()
        screenshot("$theme-app-inbox-scrolled")
        val inbox = compose.onNodeWithTag("top-fade", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val list = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent { FadeTestScreen(FadeScreen.SESSION, material3 = false, sunlight = sunlight) }
        }
        compose.waitForIdle()
        screenshot("$theme-chat-reference")
        val chat = compose.onNodeWithTag("top-fade", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("Full inbox must reach the status bar", 0f, list.top, 1f)
        assertEquals("Inbox and chat fades must start together", chat.top, inbox.top, 1f)
        assertEquals("Inbox and chat fades must have identical reach", chat.height, inbox.height, 1f)
    }

    @Test fun chatHeadlineReplacesMessagingLabel() = verifyHeadline("Mini Boss CEO", "chat-headline", false)

    @Test fun longChatHeadlineKeepsMenuVisible() = verifyHeadline(
        "Mini Boss CEO with a very long session name that must not hide the session menu",
        "chat-headline-long",
        true,
    )

    private fun verifyHeadline(name: String, screenshotName: String, ellipsized: Boolean) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent { FadeTestScreen(FadeScreen.SESSION, material3 = false, sunlight = false, sessionName = name) }
        }
        compose.waitForIdle()
        screenshot(screenshotName)
        val headline = compose.onNodeWithText(name, useUnmergedTree = true).assertIsDisplayed()
        val titleBounds = headline.fetchSemanticsNode().boundsInRoot
        val back = compose.onNodeWithContentDescription("Back").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val menu = compose.onNodeWithContentDescription("Session actions").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val fade = compose.onNodeWithTag("reader-floating-controls").fetchSemanticsNode().boundsInRoot
        assertTrue("Headline must be between back and menu", titleBounds.left >= back.right && titleBounds.right <= menu.left)
        assertTrue("Headline must remain in the top fade", titleBounds.top >= fade.top && titleBounds.bottom <= fade.bottom)
        assertTrue("Menu must retain its full width", menu.width >= back.width && menu.right <= fade.right)
        compose.onAllNodes(hasText("Messaging", substring = true)).assertCountEquals(0)
        val layouts = mutableListOf<TextLayoutResult>()
        headline.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assertEquals(1, layouts.single().lineCount)
        assertEquals(ellipsized, layouts.single().isLineEllipsized(0))
        assertEquals(ReaderForeground, layouts.single().layoutInput.style.color)
    }

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
        compose.activityRule.scenario.onActivity { activity ->
            ViewModelProvider(activity)[MainViewModel::class.java].setAppTheme(AppTheme.DARK)
        }
        compose.waitForIdle()
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
