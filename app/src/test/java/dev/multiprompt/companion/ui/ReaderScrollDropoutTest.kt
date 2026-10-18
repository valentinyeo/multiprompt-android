package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReaderScrollDropoutTest {
    @get:Rule
    val compose = createComposeRule()

    private val rows = mutableStateOf(40)
    private lateinit var scroll: ScrollState

    @Composable
    private fun Reader() {
        MaterialTheme {
            scroll = rememberReaderScrollState("session")
            Column(
                Modifier.height(240.dp).fillMaxWidth().testTag("reader").readerScroll(scroll),
            ) {
                repeat(rows.value) { index ->
                    Box(Modifier.height(80.dp)) { Text("Message $index") }
                }
            }
        }
    }

    private fun assertAtBottom() {
        compose.runOnIdle { assertEquals(scroll.maxValue, scroll.value) }
    }

    @Test
    fun chosenOffsetSurvivesContentShrinkAndRegrowth() {
        compose.setContent { Reader() }
        assertAtBottom()
        compose.onNodeWithTag("reader").performTouchInput { swipeDown() }
        val chosenOffset = compose.runOnIdle {
            assertTrue(scroll.value < scroll.maxValue)
            assertTrue("Chosen offset must be far from the start", scroll.value > 1000)
            scroll.value
        }
        compose.runOnIdle { rows.value = 2 }
        compose.runOnIdle { assertEquals(0, scroll.value) }
        compose.runOnIdle { rows.value = 40 }
        compose.runOnIdle {
            assertEquals("Restore the user's chosen offset after dropout", chosenOffset, scroll.value)
            assertTrue("Restored offset must be far from the start", scroll.value > 1000)
        }
    }

    @Test
    fun pinnedReaderStaysAtBottomThroughContentShrinkAndRegrowth() {
        compose.setContent { Reader() }
        assertAtBottom()
        compose.runOnIdle { rows.value = 2 }
        assertAtBottom()
        compose.runOnIdle { rows.value = 40 }
        assertAtBottom()
        compose.runOnIdle { rows.value = 45 }
        assertAtBottom()
    }
}
