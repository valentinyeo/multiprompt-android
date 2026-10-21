package dev.multiprompt.companion.ui

import android.app.Application
import android.view.MotionEvent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
class DeviceApprovalPromptTest {
    @get:Rule
    val compose = createComposeRule()

    private val publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAICAYGZ8wT28oTGncjtNzBoZDBnaWkiurSTzige2NVMZg igsh449-fingerprint-fixture"
    private val fingerprint = "SHA256:W2CKuicFsDaNvoYldwrvSrADDI5vhAwTk5l4O47ROHo"
    private val device = PendingDevice("pending-device", "Valentin’s laptop", fingerprint)
    private val busy = mutableStateOf(false)
    private val error = mutableStateOf<String?>(null)
    private var approvals = 0
    private var denials = 0

    private fun render() {
        compose.setContent {
            MultipromptTheme {
                DeviceApprovalPrompt(
                    device = device,
                    busy = busy.value,
                    error = error.value,
                    onApprove = { approvals++ },
                    onDeny = { denials++ },
                )
            }
        }
    }

    @Test
    fun rendersDeviceAndApprovalWarning() {
        render()
        compose.onNodeWithText("Approve this device?").assertIsDisplayed()
        compose.onNodeWithText("${device.name} wants to open your servers. Approve only if you just signed in on it.")
            .assertIsDisplayed()
        compose.onNodeWithText(fingerprint).assertIsDisplayed()
        compose.onNodeWithText("Approve").assertIsEnabled()
        compose.onNodeWithText("No").assertIsEnabled()
        compose.onNodeWithText("Adding to your servers…").assertDoesNotExist()
    }

    @Test
    fun approveCallsOnlyOnApprove() {
        render()
        compose.onNodeWithText("Approve").performClick()
        compose.runOnIdle {
            assertEquals(1, approvals)
            assertEquals(0, denials)
        }
    }

    @Test
    fun noCallsOnlyOnDeny() {
        render()
        compose.onNodeWithText("No").performClick()
        compose.runOnIdle {
            assertEquals(0, approvals)
            assertEquals(1, denials)
        }
    }

    @Test
    fun busyDisablesBothButtonsAndShowsProgressUntilFinished() {
        busy.value = true
        render()
        compose.onNodeWithText("Approve").assertIsNotEnabled().performClick()
        compose.onNodeWithText("No").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Adding to your servers…").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, approvals)
            assertEquals(0, denials)
            busy.value = false
        }
        compose.onNodeWithText("Approve").assertIsEnabled()
        compose.onNodeWithText("No").assertIsEnabled()
        compose.onNodeWithText("Adding to your servers…").assertDoesNotExist()
    }

    @Test
    fun errorIsShownAndClearedFromState() {
        render()
        compose.runOnIdle { error.value = "Could not add this device. Try again." }
        compose.onNodeWithText("Could not add this device. Try again.").assertIsDisplayed()
        compose.onNodeWithText("Approve").assertIsEnabled()
        compose.onNodeWithText("No").assertIsEnabled()
        compose.runOnIdle { error.value = null }
        compose.onNodeWithText("Could not add this device. Try again.").assertDoesNotExist()
    }

    @Test
    fun tappingOutsideDoesNotDenyOrClosePrompt() {
        render()
        compose.runOnIdle {
            val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_OUTSIDE, -1f, -1f, 0)
            try {
                assertEquals(true, ShadowDialog.getLatestDialog().onTouchEvent(event))
            } finally {
                event.recycle()
            }
            assertEquals(0, approvals)
            assertEquals(0, denials)
        }
        compose.onNodeWithText("Approve this device?").assertIsDisplayed()
        compose.onNodeWithText("No").performClick()
        compose.runOnIdle { assertEquals(1, denials) }
    }

    @Test
    fun fingerprintMatchesOpenSsh() {
        assertEquals(fingerprint, sshKeyFingerprint(publicKey))
        assertEquals(fingerprint, sshKeyFingerprint(publicKey.substringBeforeLast(' ')))
        assertEquals(fingerprint, sshKeyFingerprint("  ${publicKey.replace(" ", "\t")}\n"))
    }
}
