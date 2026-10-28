package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
class InboxTopFadeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun inboxFadeMatchesChatInDarkTheme() = verify(false)
    @Test fun inboxFadeMatchesChatInLightTheme() = verify(true)

    private fun verify(sunlight: Boolean) {
        val screen = mutableStateOf(M3Screen.INBOX_LIST)
        compose.setContent { M3TestScreen(screen.value, material3 = false, sunlight = sunlight) }
        val inbox = compose.onNodeWithTag("top-fade", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val inboxControlBottom = maxOf(
            compose.onNodeWithText("Inbox").fetchSemanticsNode().boundsInRoot.bottom,
            compose.onNodeWithContentDescription("Search sessions").fetchSemanticsNode().boundsInRoot.bottom,
        )
        compose.runOnIdle { screen.value = M3Screen.SESSION }
        val chat = compose.onNodeWithTag("top-fade", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val chatControlBottom = maxOf(
            compose.onNodeWithText("claude-reader").fetchSemanticsNode().boundsInRoot.bottom,
            compose.onNodeWithContentDescription("Back").fetchSemanticsNode().boundsInRoot.bottom,
        )
        println("FADE_BOUNDS inbox=$inbox controls=$inboxControlBottom chat=$chat controls=$chatControlBottom")
        assertEquals("Inbox fade must start alongside chat", chat.top, inbox.top, 1f)
        assertEquals("Inbox fade must extend as far below its controls as chat", chat.bottom - chatControlBottom, inbox.bottom - inboxControlBottom, 1f)
    }
}
