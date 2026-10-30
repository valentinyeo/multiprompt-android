package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.multiprompt.companion.AppUiState
import dev.multiprompt.companion.account.AccountHostMap
import dev.multiprompt.companion.account.AccountLayout
import dev.multiprompt.companion.account.FAVORITES_SPLIT_ID
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MutedInboxTest {
    @get:Rule val compose = createComposeRule()
    private val layout = AccountLayout.parse(JSONObject("""{"schema":1,"revision":1,"workspaces":[{"name":"Other"}],"tabs":[
        {"kind":"tmux","name":"visible_one","tmux_session":"visible_one","tmux_host":0,"workspace":"Other","favorite":true},
        {"kind":"tmux","name":"quiet_runner","tmux_session":"quiet_runner","tmux_host":0,"workspace":"Other","favorite":true,"muted":true}]}"""))
    private val host = HostProfile("v", "Hetzner", "vps", username = "valentin", keySecretId = "key")
    private val sessions = layout.tabs.map { TmuxSession(host.id, it.session, 1, 0, 0) }
    private val state = mutableStateOf(AppUiState(
        hosts = listOf(host), sessions = sessions, signedIn = true, accountLayout = layout,
        accountHosts = mapOf(0 to host), selectedWorkspaceId = null,
    ))

    private fun render() {
        compose.setContent {
            MaterialTheme {
                SessionsScreen(
                    state = state.value, signedIn = true, onSignIn = {}, onSignOut = {},
                    onOpen = {}, onOpenTerminal = {}, onArchive = {}, onArchiveUntil = { _, _ -> },
                    onRestore = {}, onDissolve = {}, onEnd = {}, onRestoreDissolved = {}, onForgetDissolved = {},
                    onMarkUnread = {}, onToggleArchived = {}, onSelectSessionBucket = {},
                    onSelectWorkspace = { state.value = state.value.copy(selectedWorkspaceId = it) },
                    onSwitchWorkspace = {}, onMoveWorkspaceSplit = { _, _ -> }, onResetWorkspaceSplitOrder = {},
                    onCreateWorkspace = { null }, onMoveSession = { _, _ -> }, onRenameSession = { _, _ -> null },
                    onSetFavorite = { _, _ -> }, onRefresh = {}, onSelectSection = {}, onForgetSession = {},
                    onStartNewSession = {}, onSetNewestSessionsAtBottom = {}, onSetAllSplitOnRight = {},
                    appTheme = AppTheme.SYSTEM, onSetAppTheme = {}, onClearSessionActionError = {},
                    readerDefaultFontScale = 1f, onSetReaderDefaultFontScale = {}, crashReport = null,
                    onClearCrashReport = {}, onMapAccountHost = { _, _ -> }, nowMillis = { 1_800_000_000_000 },
                )
            }
        }
    }

    @Test fun mutedSessionIsNotInTheAllInbox() {
        render()
        compose.onNodeWithText("visible_one").assertIsDisplayed()
        compose.onNodeWithText("quiet_runner").assertDoesNotExist()
    }

    @Test fun mutedStarredSessionStaysInFavorites() {
        state.value = state.value.copy(selectedWorkspaceId = FAVORITES_SPLIT_ID)
        render()
        compose.onNodeWithText("visible_one").assertIsDisplayed()
        compose.onNodeWithText("quiet_runner").assertIsDisplayed()
    }

    @Test fun mutedSessionStaysInItsWorkspaceList() {
        val tab = layout.tabs.first { it.session == "quiet_runner" }
        assertEquals(true, tab.json().get("muted"))
        assertEquals("Other", tab.workspace)
        val session = AccountHostMap.sessionFor(tab, sessions, mapOf(0 to host))
        assertEquals("quiet_runner", session?.name)
        assertEquals(tab, AccountHostMap.tabFor(sessions.first { it.name == "quiet_runner" }, layout, mapOf(0 to host)))
    }
}
