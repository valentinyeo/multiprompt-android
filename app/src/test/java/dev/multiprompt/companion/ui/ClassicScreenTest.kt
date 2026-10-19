package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
class ClassicScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settingsKeepsClassicColorsAndTypographyInBothApps() {
        val look = mutableStateOf(false to false)
        compose.setContent {
            key(look.value) { M3TestScreen(M3Screen.INBOX_LIST, look.value.first, look.value.second) }
        }
        listOf(false, true).forEach { sunlight ->
            compose.runOnIdle { look.value = false to sunlight }
            openSettings()
            val classic = settingsStyles()
            compose.runOnIdle { look.value = true to sunlight }
            openSettings()
            assertEquals("Settings must stay classic, sunlight=$sunlight", classic, settingsStyles())
        }
    }

    private fun openSettings() {
        compose.onNodeWithContentDescription("Session list actions").performClick()
        compose.onNodeWithText("Settings").performClick()
    }

    private fun settingsStyles(): List<TextStyle> = listOf(
        "Session list settings", "Newest sessions at bottom", "Theme", "Default Reader font size", "Done",
    ).map { text ->
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        layouts.single().layoutInput.style
    }
}
