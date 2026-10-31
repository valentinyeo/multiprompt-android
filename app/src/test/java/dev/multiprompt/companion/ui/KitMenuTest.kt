package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h420dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KitMenuTest {
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(scheduler))

    @Test fun rowsScrollAndPageAndReopenResetTheViewport() {
        var expanded by mutableStateOf(true)
        var page by mutableStateOf("A")
        var calls = 0
        compose.setContent {
            KitMenu(expanded, onDismissRequest = { expanded = false }, contentResetKey = page) {
                repeat(30) { index ->
                    KitMenuRow("$page row $index", onClick = { calls++ })
                }
            }
        }
        compose.onNodeWithText("A row 29").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, calls); page = "B" }
        compose.onNodeWithText("B row 0").assertIsDisplayed()
        compose.onNodeWithText("B row 29").assertIsNotDisplayed()
        compose.onNodeWithText("B row 29").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { expanded = false }
        compose.onNodeWithText("B row 29").assertDoesNotExist()
        compose.runOnIdle { expanded = true }
        compose.onNodeWithText("B row 0").assertIsDisplayed()
        compose.onNodeWithText("B row 29").assertIsNotDisplayed()
    }

    @Test fun disabledRowDoesNotDispatch() {
        var calls = 0
        compose.setContent {
            KitMenu(expanded = true, onDismissRequest = {}) {
                KitMenuRow("Unavailable", onClick = { calls++ }, enabled = false)
            }
        }
        compose.onNodeWithText("Unavailable").assertIsDisplayed().assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, calls) }
    }
}
