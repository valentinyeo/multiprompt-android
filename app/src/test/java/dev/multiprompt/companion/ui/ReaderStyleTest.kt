package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
class ReaderStyleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun readerThemeUsesNeutralCardsAndSystemTypeWithoutChangingTheAppTheme() {
        compose.setContent {
            MultipromptTheme(material3 = true) {
                val outer = MaterialTheme.colorScheme
                MultipromptTheme(material3 = true, readerStyle = true) {
                    val colors = MaterialTheme.colorScheme
                    val family = chatFontFamily()
                    val shape = chatShape()
                    val user = chatBubbleColor(true, false)
                    val agent = chatBubbleColor(false, false)
                    SideEffect {
                        assertEquals(Material3DarkColors, outer)
                        assertEquals(Color(0xFF101010), colors.background)
                        assertEquals(Color(0xFF242424), user)
                        assertEquals(user, agent)
                        assertEquals(FontFamily.SansSerif, family)
                        assertEquals(ReaderCardShape, shape)
                    }
                    Column {
                        ChatBubble(true, false) { Text("User message") }
                        ChatBubble(false, false) { Text("Agent message") }
                    }
                }
            }
        }
        val user = compose.onNodeWithText("User message").fetchSemanticsNode().boundsInRoot
        val agent = compose.onNodeWithText("Agent message").fetchSemanticsNode().boundsInRoot
        assertEquals(user.left, agent.left, 0.01f)
        assertEquals(16f, user.left, 0.01f)
        listOf("User message", "Agent message").forEach { text ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val style = layouts.single().layoutInput.style
            assertEquals(FontFamily.SansSerif, style.fontFamily)
            assertEquals(16f, style.fontSize.value)
            assertEquals(26f, style.lineHeight.value)
        }
    }

    @Test fun numberedOptionsHaveBlueLetterBadgesNextToTheirUnchangedText() {
        compose.setContent {
            MultipromptTheme(readerStyle = true) {
                MarkdownText("1. First option\n2. Second option\n27. Another option", ReaderTextSize, ReaderForeground, ReaderChoiceBlue)
            }
        }
        listOf(1 to "A", 2 to "B", 27 to "AA").forEach { (number, letter) ->
            val badge = compose.onNodeWithTag("reader-choice-$number").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(24f, badge.width, 0.01f)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(letter).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(ReaderChoiceBlue, layouts.single().layoutInput.style.color)
        }
        val badge = compose.onNodeWithTag("reader-choice-1").fetchSemanticsNode().boundsInRoot
        val option = compose.onNodeWithText("First option").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(badge.right <= option.left)
        compose.onNodeWithText("1.").assertDoesNotExist()
        compose.onNodeWithText("Second option").assertIsDisplayed()
        compose.onNodeWithText("Another option").assertIsDisplayed()
    }
}
