package dev.multiprompt.companion.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.terminal.shellHistoryLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
class ShellViewTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var switches = 0
    private var taps = 0

    @Before
    fun clearPreferences() {
        context.getSharedPreferences("session_read_state", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun shell(history: List<String> = (1..100).map { "history line $it" }, width: Int = 2000) {
        val store = SessionReadStore(context)
        compose.setContent {
            MaterialTheme {
                val defaultSize = defaultShellFontSize(MaterialTheme.typography.bodyMedium.fontSize)
                var historyVisible by remember { mutableStateOf(false) }
                var fontSize by remember {
                    mutableStateOf(store.shellFontSize()?.sp ?: defaultSize)
                }
                ShellContent(
                    historyVisible = historyVisible,
                    onOpenHistory = { historyVisible = true },
                    onJumpToLive = { historyVisible = false },
                    loadHistory = { history },
                    fontSize = fontSize,
                    onFontSizeChange = {
                        store.setShellFontSize(it)
                        fontSize = it.sp
                    },
                    contentWidth = width.dp,
                    onSwitchSession = { switches += it },
                    onLiveTap = { taps++ },
                    liveContent = { Text("Live terminal", modifier = it.fillMaxSize()) },
                )
            }
        }
    }

    @Test
    fun swipeDownRevealsEarlierHistoryAndJumpReturnsToLive() {
        shell()
        compose.onNodeWithText("Live terminal").assertIsDisplayed()
        compose.onNodeWithText("history line 60").assertDoesNotExist()
        compose.onNodeWithTag("Shell content").performTouchInput { swipeDown() }
        compose.onNodeWithTag("Shell history").assertIsDisplayed()
        compose.onNodeWithText("history line 60").assertIsDisplayed()
        compose.onNodeWithTag("Shell history").performTouchInput { swipeDown(durationMillis = 1000) }
        compose.onNodeWithText("history line 30").assertIsDisplayed()
        compose.onNodeWithContentDescription("Jump to live").performClick()
        compose.onNodeWithTag("Shell history").assertDoesNotExist()
        compose.onNodeWithText("Live terminal").assertIsDisplayed()
    }

    @Test
    fun scrollingPastNewestHistoryReturnsToLive() {
        shell(history = listOf("newest history"))
        compose.onNodeWithTag("Shell content").performTouchInput { swipeDown() }
        compose.onNodeWithTag("Shell history").performTouchInput { swipeUp() }
        compose.onNodeWithTag("Shell history").assertDoesNotExist()
        compose.onNodeWithText("Live terminal").assertIsDisplayed()
    }

    @Test
    fun defaultFontForWideDesktopPaneIsAtLeastSystemBodySize() {
        var measured = false
        compose.setContent {
            MaterialTheme {
                val paneColumns = 200
                val width = LocalConfiguration.current.screenWidthDp
                val bodySize = MaterialTheme.typography.bodyMedium.fontSize
                val actualSize = defaultShellFontSize(bodySize)
                assertEquals(390, width)
                assertTrue(paneColumns * actualSize.value > width)
                assertTrue(actualSize >= bodySize)
                measured = true
            }
        }
        compose.runOnIdle { assertTrue(measured) }
    }

    @Test
    fun pinchZoomIsPersistedAndRestored() {
        shell()
        compose.onNodeWithTag("Shell content").performTouchInput {
            pinch(
                start0 = Offset(130f, 300f),
                end0 = Offset(50f, 300f),
                start1 = Offset(250f, 300f),
                end1 = Offset(330f, 300f),
            )
        }
        compose.runOnIdle {
            val chosen = SessionReadStore(context).shellFontSize()
            assertTrue("Pinch must save an enlarged size", chosen != null && chosen > 14f)
            assertEquals(chosen, SessionReadStore(context).shellFontSize())
            assertEquals(28f, zoomShellFontSize(20f, 3f))
            assertEquals(8f, zoomShellFontSize(14f, 0.1f))
        }
    }

    @Test
    fun horizontalPanDoesNotSwitchUntilAnEdgeAndTapStillWorks() {
        shell()
        val initial = compose.onNodeWithText("Live terminal").getUnclippedBoundsInRoot()
        assertEquals(0f, initial.left.value, 1f)
        compose.onNodeWithTag("Shell content").performTouchInput { swipeLeft() }
        val panned = compose.onNodeWithText("Live terminal").getUnclippedBoundsInRoot()
        assertTrue(panned.left < initial.left)
        compose.runOnIdle { assertEquals(0, switches) }
        repeat(4) { compose.onNodeWithTag("Shell content").performTouchInput { swipeRight() } }
        compose.onNodeWithTag("Shell content").performTouchInput { swipeRight() }
        compose.runOnIdle { assertTrue(switches < 0) }
        compose.onNodeWithTag("Shell content").performTouchInput {
            down(center)
            up()
        }
        compose.runOnIdle { assertEquals(1, taps) }
    }

    @Test
    fun capturedHistoryStripsAnsiAndControlsAndBoundsLineCount() {
        assertEquals(
            listOf("red", "link\ttext"),
            shellHistoryLines("\u001b[31mred\u001b[0m\r\n\u001b]8;;https://example.com\u0007link\u001b]8;;\u001b\\\ttext\u0000"),
        )
        val lines = shellHistoryLines((1..5100).joinToString("\n"))
        assertEquals(5000, lines.size)
        assertEquals("101", lines.first())
    }
}
