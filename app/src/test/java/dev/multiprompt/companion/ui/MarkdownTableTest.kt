package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.width
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
class MarkdownTableTest {
    @get:Rule
    val compose = createComposeRule()

    private fun render(fixture: String, material3: Boolean = false) {
        val source = checkNotNull(javaClass.getResource("/$fixture")).readText()
        compose.setContent {
            MultipromptTheme(material3 = material3) {
                Box(Modifier.width(390.dp)) {
                    MarkdownText(source, 14.sp, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    private fun assertTableCellsDisplayed() {
        val expected = listOf(
            "", "Flag on", "Flag off", "Desktop", "0.006 to 0.007 over 13 cold loads",
            "0.058 to 0.064 over 5 cold loads", "Phone, 4x CPU", "0 over 5 cold loads", "0 over 5 cold loads",
        )
        val cells = compose.onAllNodesWithTag("md-cell")
        cells.assertCountEquals(expected.size)
        expected.forEachIndexed { index, text ->
            cells[index].assert(hasText(text)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun pipeTableDisplaysRealCellsAndScrollsWithinPhoneWidth() {
        render("table-fixture.md")
        assertTableCellsDisplayed()
        val table = compose.onNodeWithTag("md-table")
        val range = table.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue("Table must scroll horizontally", range.maxValue() > 0f)
        assertTrue(table.getUnclippedBoundsInRoot().width <= 390.dp)
        val results = ArrayList<TextLayoutResult>()
        compose.onNode(hasTestTag("md-cell") and hasText("0.058 to 0.064 over 5 cold loads"))
            .performScrollTo().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(1, results.single().lineCount)
        results.clear()
        compose.onNode(hasTestTag("md-cell") and hasText("Flag on"))
            .performScrollTo().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(FontWeight.Bold, results.single().layoutInput.style.fontWeight)
        val cells = compose.onAllNodesWithTag("md-cell")
        for (column in 0..2) {
            val header = cells[column].getUnclippedBoundsInRoot()
            for (row in 1..2) {
                val cell = cells[row * 3 + column].getUnclippedBoundsInRoot()
                assertEquals(header.left, cell.left)
                assertEquals(header.width, cell.width)
                assertTrue(cell.width <= 280.dp)
            }
        }
    }

    @Test
    fun terminalTableDisplaysJoinedCellsWithoutBoxDrawingCharacters() {
        render("table-fixture.terminal.txt")
        assertTableCellsDisplayed()
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())
        nodes.forEach { node ->
            assertTrue(node.config[SemanticsProperties.Text].none { text -> text.any { it in '\u2500'..'\u257f' } })
        }
    }

    @Test
    fun material3TableKeepsContentSizedAlignedColumnsAndUsesSemiboldHeader() {
        render("table-fixture.md", material3 = true)
        assertTableCellsDisplayed()
        val results = ArrayList<TextLayoutResult>()
        compose.onNode(hasTestTag("md-cell") and hasText("Flag on"))
            .performScrollTo().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(FontWeight.SemiBold, results.single().layoutInput.style.fontWeight)
        val cells = compose.onAllNodesWithTag("md-cell")
        for (column in 0..2) {
            val header = cells[column].getUnclippedBoundsInRoot()
            for (row in 1..2) {
                val cell = cells[row * 3 + column].getUnclippedBoundsInRoot()
                assertEquals(header.left, cell.left)
                assertEquals(header.width, cell.width)
                assertTrue(cell.width <= 280.dp)
            }
        }
        val range = compose.onNodeWithTag("md-table").fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue(range.maxValue() > 0f)
    }

}
