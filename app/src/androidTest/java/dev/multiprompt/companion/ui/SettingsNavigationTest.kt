package dev.multiprompt.companion.ui

import android.Manifest
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.multiprompt.companion.AppUiState
import dev.multiprompt.companion.MainActivity
import dev.multiprompt.companion.MainViewModel
import dev.multiprompt.companion.MultipromptApplication
import dev.multiprompt.companion.account.AccountAuth
import dev.multiprompt.companion.account.AccountLayoutStore
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import dev.multiprompt.companion.update.UpdateState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsNavigationTest {
    @get:Rule(order = 0) val notifications = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: MainViewModel
    private lateinit var app: MultipromptApplication
    private val host = HostProfile("settings-host", "Fixture server", "example.invalid", username = "valentin")

    @Suppress("UNCHECKED_CAST")
    private fun start(theme: AppTheme) {
        compose.activityRule.scenario.onActivity { activity ->
            app = activity.application as MultipromptApplication
            app.secretStore.remove(AccountAuth.PENDING)
            (0..2).forEach { AccountLayoutStore(app).setHost(it, null) }
            model = ViewModelProvider(activity)[MainViewModel::class.java]
            val field = MainViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
            val state = field.get(model) as MutableStateFlow<AppUiState>
            state.value = AppUiState(signedIn = true, appTheme = theme, hosts = listOf(host),
                sessions = listOf(TmuxSession(host.id, "Fixture session", 1, 0, 0)))
            val updates = model.updates.javaClass.getDeclaredField("_state").apply { isAccessible = true }
            (updates.get(model.updates) as MutableStateFlow<UpdateState>).value = UpdateState.Checking
        }
        compose.onNodeWithText("Inbox").assertIsDisplayed()
        compose.onNodeWithText("All").performClick()
        compose.onNodeWithText("Fixture session").assertIsDisplayed()
    }

    private fun openSettings() {
        compose.onNodeWithContentDescription("Session list actions").performClick()
        compose.onNodeWithText("Hosts").assertDoesNotExist()
        compose.onNodeWithText("Workspaces").assertDoesNotExist()
        compose.onNodeWithText("Update").assertDoesNotExist()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Session list settings").assertIsDisplayed()
    }

    @Test fun darkHomeAndSettingsKeepOnlySupportedDestinations() = verify(AppTheme.DARK, "dark")
    @Test fun lightHomeAndSettingsKeepOnlySupportedDestinations() = verify(AppTheme.SUNLIGHT, "light")

    private fun verify(theme: AppTheme, name: String) {
        start(theme)
        screenshot("$name-home")
        openSettings()
        compose.onNodeWithText("Switch / renew login").assertIsDisplayed()
        compose.onNodeWithText("Sign out").assertIsDisplayed()
        compose.onNodeWithText("Update").assertIsDisplayed()
        screenshot("$name-settings")
        compose.onNodeWithText("Host mapping").performClick()
        compose.onNodeWithText("Desktop tmux hosts").assertIsDisplayed()
        shell("input keyevent 4")
        compose.onNodeWithText("Session list settings").assertIsDisplayed()
        compose.onNodeWithText("Host mapping").performClick()
        compose.onNodeWithText("0 · Hetzner: Not mapped").performClick()
        compose.onNodeWithText("Automatic").assertIsDisplayed()
        shell("input keyevent 4")
        compose.onNodeWithText("0 · Hetzner: Not mapped").assertIsDisplayed()
        compose.onNodeWithText("0 · Hetzner: Not mapped").performClick()
        compose.onNodeWithText("Fixture server · valentin@example.invalid").performClick()
        compose.runOnIdle { assertEquals(host.id, AccountLayoutStore(app).hostOverrides()[0]) }
        compose.onNodeWithText("0 · Hetzner: Fixture server (valentin)").assertIsDisplayed()
        screenshot("$name-mapping")
        compose.onNodeWithText("0 · Hetzner: Fixture server (valentin)").performClick()
        compose.onNodeWithText("Automatic").performClick()
        compose.runOnIdle { assertNull(AccountLayoutStore(app).hostOverrides()[0]) }
        compose.onNodeWithText("0 · Hetzner: Not mapped").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Update").performClick()
        compose.onNodeWithText("Update", substring = false).assertIsDisplayed()
        shell("input keyevent 4")
        compose.onNodeWithText("Inbox").assertIsDisplayed()
        openSettings()
        compose.onNodeWithText("Switch / renew login").performClick()
        compose.waitUntil(10_000) { app.secretStore.get(AccountAuth.PENDING) != null }
        screenshot("$name-renew-browser")
        shell("input keyevent 4")
        compose.onNodeWithText("Session list settings").assertIsDisplayed()
        compose.onNodeWithText("Sign out").performClick()
        compose.onNodeWithText("Sign in").assertIsDisplayed()
        compose.waitUntil(10_000) { app.secretStore.get(AccountAuth.PENDING) == null }
        screenshot("$name-signed-out")
        compose.onNodeWithText("Sign in").performClick()
        compose.waitUntil(10_000) { app.secretStore.get(AccountAuth.PENDING) != null }
        screenshot("$name-sign-in-browser")
    }

    private fun shell(command: String) {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        shell("mkdir -p /data/local/tmp/settings-proof")
        shell("screencap -p /data/local/tmp/settings-proof/$name.png")
    }
}
