package dev.multiprompt.companion.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
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
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.width
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
class ChatTableExpandTest {
    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(scheduler))

    private fun show(source: String, sunlight: Boolean = false) {
        compose.setContent {
            MultipromptTheme(sunlight = sunlight) {
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
        compose.onNodeWithTag("table-sheet-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100000f) }
        compose.waitForIdle()
        sheet.assertIsDisplayed()
        compose.onNodeWithTag("table-close").performClick()
        compose.onNodeWithTag("md-table-expanded").assertDoesNotExist()
        compose.onNodeWithTag("table-expand").assertIsDisplayed()
        compose.onAllNodesWithText("Open plans").assertCountEquals(1)
    }

    @Test
    fun theExpandedTablePaintsDarkTextOnTheLightSheet() {
        assertSheetContrast(sunlight = true)
    }

    @Test
    fun theExpandedTablePaintsLightTextOnTheDarkSheet() {
        assertSheetContrast(sunlight = false)
    }

    private fun assertSheetContrast(sunlight: Boolean) {
        show("## Garden harvest table\n\n| Plot | Northern beds |\n| --- | --- |\n| R01-C01 orchard | R01-C02 orchard |\n", sunlight)
        compose.onNodeWithTag("table-expand").performClick()
        compose.waitForIdle()
        val contrast = paintedContrast(capture("md-table-expanded"))
        assertTrue(
            "the expanded table must paint its cells in the theme's text colour, contrast was $contrast with sunlight=$sunlight",
            contrast > 100,
        )
    }

    private fun paintedContrast(bitmap: Bitmap): Double {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val luma = pixels.filter { (it ushr 24) > 0 }.map { pixel ->
            0.2126 * ((pixel shr 16) and 255) + 0.7152 * ((pixel shr 8) and 255) + 0.0722 * (pixel and 255)
        }
        assertTrue("the sheet painted nothing", luma.isNotEmpty())
        return luma.max() - luma.min()
    }

    private fun capture(tag: String): Bitmap {
        val root = compose.onNodeWithTag(tag)
        val view = (checkNotNull(root.fetchSemanticsNode().root) as ViewRootForTest).view.rootView
        val attachInfo = ReflectionHelpers.getField<Any>(view, "mAttachInfo")
        val accelerated = view.isHardwareAccelerated
        ReflectionHelpers.setField(attachInfo, "mHardwareAccelerated", false)
        val redraw = CoroutineScope(UnconfinedTestDispatcher(scheduler)).launch {
            while (isActive) {
                delay(16)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
                view.viewTreeObserver.dispatchOnDraw()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
        return try {
            root.captureToImage().asAndroidBitmap()
        } finally {
            redraw.cancel()
            ReflectionHelpers.setField(attachInfo, "mHardwareAccelerated", accelerated)
        }
    }
}
