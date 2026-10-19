package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SignInMenuTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun signedOutMenuSignsIn() {
        var signInCalls = 0
        var signOutCalls = 0
        compose.setContent {
            MaterialTheme {
                InboxOverflowMenu(
                    signedIn = false,
                    onSignIn = { signInCalls++ },
                    onSignOut = { signOutCalls++ },
                    onSettings = {},
                    onArrangeSplits = {},
                    onNewWorkspace = {},
                    onRefresh = {},
                    onHosts = {},
                    onUpdate = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Session list actions").performClick()
        compose.onNodeWithText("Sign in to sync").assertIsDisplayed().performClick()
        compose.onNodeWithText("Sign in to sync").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, signInCalls)
            assertEquals(0, signOutCalls)
        }
    }

    @Test
    fun signedInMenuSignsOut() {
        var signInCalls = 0
        var signOutCalls = 0
        compose.setContent {
            MaterialTheme {
                InboxOverflowMenu(
                    signedIn = true,
                    onSignIn = { signInCalls++ },
                    onSignOut = { signOutCalls++ },
                    onSettings = {},
                    onArrangeSplits = {},
                    onNewWorkspace = {},
                    onRefresh = {},
                    onHosts = {},
                    onUpdate = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Session list actions").performClick()
        compose.onNodeWithText("Sync: signed in — sign out").assertIsDisplayed().performClick()
        compose.onNodeWithText("Sync: signed in — sign out").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, signInCalls)
            assertEquals(1, signOutCalls)
        }
    }
}
