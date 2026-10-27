package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlainListsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun plainNumberedListsKeepTheirNumbersAndHaveNoCards() {
        compose.setContent {
            MultipromptTheme(readerStyle = true) {
                MarkdownText("Which route?\n\n1. First route\n2. Second route\n\n- Ordinary bullet", ReaderTextSize, ReaderForeground, ReaderChoiceBlue)
            }
        }
        compose.onNodeWithText("1.").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText("2.").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText("First route").assertHasNoClickAction()
        compose.onNodeWithText("Ordinary bullet").assertHasNoClickAction()
        compose.onNodeWithTag("reader-choice-1").assertDoesNotExist()
    }
}
