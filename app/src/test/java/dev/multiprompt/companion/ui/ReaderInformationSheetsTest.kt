package dev.multiprompt.companion.ui

import android.app.Application
import androidx.activity.ComponentDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import dev.multiprompt.companion.ssh.TmuxText
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.robolectric.shadows.ShadowDialog

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderInformationSheetsTest {
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(scheduler))

    private fun text(value: String) = compose.onNode(hasText(value) and hasAnyAncestor(isDialog()))

    @Test fun accuracyBranchesPriorityAndClose() {
        var loading by mutableStateOf(true)
        var error by mutableStateOf<String?>("Problem")
        var rows by mutableStateOf(listOf(TmuxText.AgentAccuracy("Agent", 1.5, 3)))
        var dismissals = 0
        compose.setContent { EstimateAccuracySheet(rows, loading, error) { dismissals++ } }
        text("Reading the estimate log…").assertIsDisplayed()
        text("Problem").assertDoesNotExist()
        compose.runOnIdle { loading = false }
        text("Problem").assertIsDisplayed()
        compose.runOnIdle { error = null; rows = emptyList() }
        text("No finished estimates yet. A finished turn counts when it ran two minutes or more, and an agent needs three of them.").assertIsDisplayed()
        text("Close").performClick()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun accuracyUsesUsDecimalAndInputOrder() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            compose.setContent {
                EstimateAccuracySheet(
                    listOf(TmuxText.AgentAccuracy("First", 1.25, 3), TmuxText.AgentAccuracy("Second", 2.5, 8)),
                    false, null, {},
                )
            }
            val first = text("First: tasks take 1.3x the estimate (last 3)").assertIsDisplayed().fetchSemanticsNode()
            val second = text("Second: tasks take 2.5x the estimate (last 8)").assertIsDisplayed().fetchSemanticsNode()
            assertTrue(first.positionInRoot.y < second.positionInRoot.y)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun accuracySystemBackDismisses() {
        var dismissals = 0
        compose.setContent { EstimateAccuracySheet(emptyList(), false, null) { dismissals++ } }
        text("Close").assertIsDisplayed()
        compose.runOnIdle { (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressed() }
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun historySystemBackDismisses() {
        var dismissals = 0
        compose.setContent { SessionHistorySheet(MutableStateFlow(listOf("History")), { false }) { dismissals++ } }
        text("Back to live").assertIsDisplayed()
        compose.runOnIdle { (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressed() }
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    @Config(qualifiers = "w390dp-h400dp-mdpi")
    fun historyShortViewportKeepsCloseReachable() {
        val pages = MutableStateFlow(listOf((1..100).joinToString("\n") { "Line $it" }))
        var dismissals = 0
        compose.setContent { SessionHistorySheet(pages, { false }) { dismissals++ } }
        val list = compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog())).assertIsDisplayed()
        val close = text("Back to live").assertIsDisplayed()
        assertTrue(list.getUnclippedBoundsInRoot().bottom <= close.getUnclippedBoundsInRoot().top)
        close.performClick()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun historyLoadingAndExhaustion() {
        val pages = MutableStateFlow<List<String>>(emptyList())
        val first = CompletableDeferred<Boolean>()
        val second = CompletableDeferred<Boolean>()
        var calls = 0
        var dismissals = 0
        compose.setContent {
            SessionHistorySheet(pages, { calls++; if (calls == 1) first.await() else second.await() }) { dismissals++ }
        }
        text("Capturing…").assertIsDisplayed()
        compose.runOnIdle { pages.value = listOf("Older page", "Newer page") }
        val newer = text("Newer page").assertIsDisplayed().fetchSemanticsNode()
        val older = text("Older page").assertIsDisplayed().fetchSemanticsNode()
        assertTrue(newer.positionInRoot.y < older.positionInRoot.y)
        val layouts = mutableListOf<TextLayoutResult>()
        text("Newer page").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(FontFamily.Monospace, layouts.single().layoutInput.style.fontFamily)
        assertEquals(11.sp, layouts.single().layoutInput.style.fontSize)
        text("Load older").performClick()
        text("Loading…").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, calls); first.complete(true) }
        text("Load older").performClick()
        text("Loading…").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(2, calls); second.complete(false) }
        text("No older history available.").assertIsDisplayed()
        text("Load older").assertIsNotEnabled()
        text("Back to live").performClick()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }
}
