package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.width
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
class ChatTableExpandTest {
    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(scheduler))

    private fun show(source: String) {
        compose.setContent {
            MultipromptTheme {
                Box(Modifier.width(390.dp)) {
                    MarkdownText(source, 14.sp, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    @Test
    fun proseHasNoExpandButton() {
        show("Just a sentence.")
        compose.onNodeWithContentDescription("Expand table").assertDoesNotExist()
    }

    @Test
    fun eachTableHasItsOwnExpandButton() {
        show("| A |\n| --- |\n| 1 |\n\n| B |\n| --- |\n| 2 |\n")
        compose.onAllNodesWithContentDescription("Expand table").assertCountEquals(2)
    }

    @Test
    fun sheetShowsTheFullTableAndCloseReturnsToTheChatTable() {
        val wide = "w".repeat(120)
        val last = "end-$wide"
        val source = buildString {
            append("# Open plans\n\n| Plan |\n| --- |\n")
            repeat(24) { append("| row-$it-$wide |\n") }
            append("| $last |\n")
        }
        show(source)
        val chat = compose.onNode(hasTestTag("md-cell") and hasText(last) and hasAnyAncestor(hasTestTag("md-table")))
        assertTrue(chat.getUnclippedBoundsInRoot().width <= 280.dp)
        compose.onNodeWithTag("table-expand").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("table-sheet").assertIsDisplayed()
        compose.onAllNodesWithText("Open plans").assertCountEquals(2)
        val sheet = compose.onNode(hasTestTag("md-cell") and hasText(last) and hasAnyAncestor(hasTestTag("md-table-expanded")))
        assertTrue(sheet.getUnclippedBoundsInRoot().width > 280.dp)
        val results = ArrayList<TextLayoutResult>()
        sheet.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(1, results.single().lineCount)
        sheet.performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("table-close").performClick()
        compose.onNodeWithTag("md-table-expanded").assertDoesNotExist()
        compose.onNodeWithTag("table-expand").assertIsDisplayed()
        compose.onAllNodesWithText("Open plans").assertCountEquals(1)
    }
}
