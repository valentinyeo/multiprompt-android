package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
class CodeBlockCopyTest {
    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(scheduler))

    private lateinit var clipboard: ClipboardManager

    private fun show(source: String, nativeChat: Boolean = false) {
        compose.setContent {
            if (nativeChat) {
                MaterialTheme {
                    clipboard = LocalClipboardManager.current
                    Box(Modifier.width(320.dp).background(MaterialTheme.colorScheme.surface)) {
                        MarkdownText(
                            source,
                            15.sp,
                            MaterialTheme.colorScheme.onSurface,
                            MaterialTheme.colorScheme.primary,
                            imagePreviews = true,
                        )
                    }
                }
            } else {
                MultipromptTheme(readerStyle = true) {
                    clipboard = LocalClipboardManager.current
                    Box(Modifier.width(320.dp)) {
                        MarkdownText(source, 16.sp, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    private fun codeLayout(): TextLayoutResult {
        val results = ArrayList<TextLayoutResult>()
        compose.onNodeWithTag("md-code").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    @Test
    fun longSingleLinePromptWrapsInsideTheBubble() {
        val prompt = "/pstack:poteto-mode make one Multiprompt bug fix from ticket 2959 and get it published"
        show("If you'd rather a fresh chat finish this, here's the prompt:\n\n```\n$prompt\n```\n")
        val layout = codeLayout()
        assertEquals(prompt, layout.layoutInput.text.text)
        assertTrue(
            "the code block drew ${layout.lineCount} line(s), so the prompt is cut off at the right edge",
            layout.lineCount > 1,
        )
        repeat(layout.lineCount) { line ->
            assertTrue(
                "line $line ends at ${layout.getLineRight(line)} past the ${layout.size.width}px block width",
                layout.getLineRight(line) <= layout.size.width,
            )
        }
    }

    @Test
    fun spaceSeparatedEightHundredCharLineShowsInNativeChatBubble() {
        val body = buildString {
            while (length < 800) {
                if (isNotEmpty()) append(' ')
                append("tok%03d".format(length))
            }
        }
        assertTrue(body.length >= 800)
        val source = "Before the fence.\n\n```\n$body\n```\n\nAfter the fence."
        show(source, nativeChat = true)
        val layout = codeLayout()
        assertEquals(body, layout.layoutInput.text.text)
        assertTrue(
            "fenced code must occupy vertical space, got ${layout.lineCount} line(s) and ${layout.size.height}px height",
            layout.lineCount >= 1 && layout.size.height > 0,
        )
        assertTrue(
            "at least one code glyph must paint inside the block width",
            layout.getLineRight(0) > 0f,
        )
        assertTrue(
            "one-line fences must wrap inside the bubble instead of hiding in horizontal scroll",
            layout.lineCount > 1,
        )
    }

    @Test
    fun unlabelledFenceKeepsLongSingleToken() {
        val body = "X".repeat(800)
        val source = "Intro.\n\n```\n$body\n```\n\nOutro."
        show(source)
        val layout = codeLayout()
        assertEquals(body, layout.layoutInput.text.text)
        assertTrue(layout.lineCount > 1)
        assertTrue(layout.size.height > layout.getLineBottom(0) - layout.getLineTop(0))
    }

    @Test
    fun copyControlPutsTheExactBlockTextOnTheClipboard() {
        val body = "/pstack:poteto-mode make one Multiprompt bug fix\nSecond line stays exactly as sent"
        show("Run this:\n\n```\n$body\n```\n")
        compose.onNodeWithContentDescription("Copy code").performClick()
        compose.runOnIdle { assertEquals(body, clipboard.getText()?.text) }
    }
}
