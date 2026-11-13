package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.multiprompt.companion.computer.ComputerControl
import dev.multiprompt.companion.computer.ComputerState
import dev.multiprompt.companion.computer.ComputerStatus
import dev.multiprompt.companion.computer.ComputerTarget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComputerPaneTest {
    @get:Rule val compose = createComposeRule()
    private var woken = 0
    private var slept = 0
    private var backed = 0
    private var addressEdited = 0
    private var shown: ComputerControl? = null
    private val state = mutableStateOf(ComputerState())

    private fun show(initial: ComputerState) {
        state.value = initial
        compose.setContent {
            MultipromptTheme {
                ComputerPane(
                    hostLabel = "VPS",
                    state = state.value,
                    target = ComputerTarget.Default,
                    picture = null,
                    onBack = { backed++ },
                    onWake = { woken++ },
                    onSleep = { slept++ },
                    onEditTarget = { addressEdited++ },
                    screen = { modifier, control ->
                        shown = control
                        Box(modifier.padding(4.dp).then(Modifier.fillMaxSize())) { Text("live picture") }
                    },
                )
            }
        }
    }

    @Test fun liveShowsTheScreenAndSaysItIsViewOnly() {
        show(ComputerState(ComputerStatus.Live, localPort = 45123))
        compose.onNodeWithText("Computer").assertIsDisplayed()
        compose.onNodeWithText(" · VPS").assertIsDisplayed()
        compose.onNodeWithText("Live").assertIsDisplayed()
        compose.onNodeWithText("View only").assertIsDisplayed()
        compose.onNodeWithText("Agents keep working. Take control to click and type.").assertIsDisplayed()
        compose.onNodeWithText("live picture").assertIsDisplayed()
        compose.onNodeWithText("Open full screen").assertIsDisplayed()
        compose.onNodeWithText("Take control").assertIsDisplayed()
        assertEquals(false, shown?.enabled)
    }

    @Test fun takeControlHandsTapsAndKeysToTheServerAndGiveBackEndsIt() {
        show(ComputerState(ComputerStatus.Live, localPort = 45123))
        compose.onNodeWithText("Take control").performClick()
        compose.onNodeWithText("In control").assertIsDisplayed()
        compose.onNodeWithText("A tap clicks on the VPS. Keyboard types there.").assertIsDisplayed()
        assertEquals(true, shown?.enabled)
        compose.onNodeWithText("Keyboard").performClick()
        compose.runOnIdle { assertEquals(ComputerControl(enabled = true, keyboardRequests = 1), shown) }
        compose.onNodeWithText("Give back").performClick()
        compose.onNodeWithText("View only").assertIsDisplayed()
        compose.onNodeWithText("Take control").assertIsDisplayed()
        assertEquals(false, shown?.enabled)
    }

    @Test fun controlEndsWhenTheScreenSleepsAndViewOnlyComesBack() {
        show(ComputerState(ComputerStatus.Live, localPort = 45123))
        compose.onNodeWithText("Take control").performClick()
        compose.runOnIdle { assertEquals(true, shown?.enabled) }
        state.value = ComputerState(ComputerStatus.Sleeping)
        compose.waitForIdle()
        compose.onNodeWithText("Take control").assertIsNotEnabled()
        state.value = ComputerState(ComputerStatus.Live, localPort = 45123)
        compose.waitForIdle()
        compose.onNodeWithText("View only").assertIsDisplayed()
        assertEquals(false, shown?.enabled)
    }

    @Test fun fullScreenGivesControlBackInOneTap() {
        show(ComputerState(ComputerStatus.Live, localPort = 45123))
        compose.onNodeWithText("Take control").performClick()
        compose.onNodeWithText("Open full screen").performClick()
        compose.onNodeWithText("Give back").performClick()
        compose.runOnIdle { assertEquals(false, shown?.enabled) }
        compose.onNodeWithText("Exit full screen").performClick()
        compose.onNodeWithText("View only").assertIsDisplayed()
    }

    @Test fun firstOpenExplainsThatThereIsNoPictureYet() {
        show(ComputerState(ComputerStatus.Connecting))
        compose.onNodeWithText("Connecting to the VPS").assertIsDisplayed()
        compose.onNodeWithText("First open. No picture yet.").assertIsDisplayed()
        compose.onNodeWithText("Connecting").assertIsDisplayed()
    }

    @Test fun sleepingKeepsTheLastPictureAndWakesInOneTap() {
        show(ComputerState(ComputerStatus.Sleeping))
        compose.onAllNodesWithText("Sleeping").assertCountEquals(2)
        compose.onNodeWithText("Tap to wake.").assertIsDisplayed()
        compose.onNodeWithText("Wake up").performClick()
        assertEquals(1, woken)
    }

    @Test fun aLostViewerOffersOneRetryAndSaysAgentsStillWork() {
        show(ComputerState(ComputerStatus.Lost, failure = "Cannot reach the VPS"))
        compose.onAllNodesWithText("Connection lost").assertCountEquals(2)
        compose.onNodeWithText("Cannot reach the VPS").assertIsDisplayed()
        compose.onNodeWithText("Agents keep working. Only the picture is gone.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, woken)
    }

    @Test fun theBackArrowReturnsToTheChatInOneTap() {
        show(ComputerState(ComputerStatus.Live, localPort = 45123))
        compose.onNodeWithContentDescription("Back to chat").performClick()
        assertEquals(1, backed)
    }
}
