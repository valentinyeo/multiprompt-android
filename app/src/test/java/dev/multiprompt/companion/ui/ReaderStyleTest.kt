package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
                        assertEquals(Color(0xFF1F1F1F), colors.surfaceContainerHighest)
                        assertEquals(Color(0xFF3A3A3A), user)
                        assertEquals(Color(0xFF1E1E1E), agent)
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
        assertEquals(12f, agent.left, 0.01f)
        assertEquals(378f, user.right, 0.5f)
        assertTrue(user.left > agent.left)
        listOf("User message", "Agent message").forEach { text ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val style = layouts.single().layoutInput.style
            assertEquals(FontFamily.SansSerif, style.fontFamily)
            assertEquals(16f, style.fontSize.value)
            assertEquals(26f, style.lineHeight.value)
        }
    }

    @Test fun markdownKeepsSystemFontAndThemeSizesWhenZoomed() {
        compose.setContent {
            MultipromptTheme(readerStyle = true) {
                MarkdownText("# Heading\n\nInline `code`\n\n```\ncode sample\n```", ReaderTextSize * 1.5f, ReaderForeground, ReaderChoiceBlue)
            }
        }
        listOf(Triple("Heading", 30f, 45f), Triple("Inline code", 24f, 39f), Triple("code sample", 21f, 33f)).forEach { (text, size, height) ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals(FontFamily.SansSerif, layout.layoutInput.style.fontFamily)
            assertEquals(size, layout.layoutInput.style.fontSize.value)
            assertEquals(height, layout.layoutInput.style.lineHeight.value)
            if (text == "Inline code") assertTrue(layout.layoutInput.text.spanStyles.any { it.item.fontFamily == FontFamily.SansSerif })
        }
    }

    @Test fun shortMarkdownRepliesShrinkInsideTheirBubbles() {
        compose.setContent {
            MultipromptTheme(readerStyle = true) {
                Column {
                    ChatBubble(false, false, contentModifier = Modifier.testTag("short-reply")) {
                        MarkdownText("Ready", ReaderTextSize, ReaderForeground, ReaderChoiceBlue, imagePreviews = true)
                    }
                    ChatBubble(false, false, contentModifier = Modifier.testTag("long-reply")) {
                        MarkdownText("A long reply with enough words to fill the available message width and wrap onto another line.", ReaderTextSize, ReaderForeground, ReaderChoiceBlue, imagePreviews = true)
                    }
                }
            }
        }
        val short = compose.onNodeWithTag("short-reply").fetchSemanticsNode().boundsInRoot
        val long = compose.onNodeWithTag("long-reply").fetchSemanticsNode().boundsInRoot
        assertTrue("Short replies shrink around their text", short.width < long.width / 2)
        assertEquals(short.left, long.left, 0.01f)
    }

    @Test fun numberedListsKeepTheirOriginalMarkersWithoutAnswerBadges() {
        compose.setContent {
            MultipromptTheme(readerStyle = true) {
                MarkdownText("1. First option\n2. Second option\n27. Another option", ReaderTextSize, ReaderForeground, ReaderChoiceBlue)
            }
        }
        listOf("1.", "2.", "27.", "First option", "Second option", "Another option").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        listOf(1, 2, 27).forEach { compose.onNodeWithTag("reader-choice-$it").assertDoesNotExist() }
    }
}
