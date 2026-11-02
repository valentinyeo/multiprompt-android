package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PagePaneTest {
    @get:Rule val compose = createComposeRule()
    private var backed = 0
    private var browsed = 0

    private fun show() {
        compose.setContent {
            MultipromptTheme {
                PagePane(
                    title = "example.com",
                    onBack = { backed++ },
                    onOpenInBrowser = { browsed++ },
                    screen = { modifier -> Box(modifier) { Text("page body") } },
                )
            }
        }
    }

    @Test fun thePageSitsBesideTheChatAndReturnsInOneTap() {
        show()
        compose.onNodeWithText("Page").assertIsDisplayed()
        compose.onNodeWithText(" · example.com").assertIsDisplayed()
        compose.onNodeWithText("page body").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to chat").performClick()
        assertEquals(1, backed)
    }

    @Test fun thePageMenuStillReachesTheRealBrowser() {
        show()
        compose.onNodeWithContentDescription("Page actions").performClick()
        compose.onNodeWithText("Open in browser").performClick()
        assertEquals(1, browsed)
    }
}
