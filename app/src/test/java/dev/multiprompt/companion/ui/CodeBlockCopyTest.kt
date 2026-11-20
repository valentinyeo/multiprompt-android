package dev.multiprompt.companion.ui

import android.app.Application
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

    private fun show(source: String) {
        compose.setContent {
            MultipromptTheme(readerStyle = true) {
                clipboard = LocalClipboardManager.current
                Box(Modifier.width(320.dp)) {
                    MarkdownText(source, 16.sp, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.primary)
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
    fun copyControlPutsTheExactBlockTextOnTheClipboard() {
        val body = "/pstack:poteto-mode make one Multiprompt bug fix\nSecond line stays exactly as sent"
        show("Run this:\n\n```\n$body\n```\n")
        compose.onNodeWithContentDescription("Copy code").performClick()
        compose.runOnIdle { assertEquals(body, clipboard.getText()?.text) }
    }
}
