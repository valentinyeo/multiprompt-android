package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.multiprompt.companion.AppUiState
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class QuietInboxTest {
    @get:Rule val compose = createComposeRule()

    @Test fun refreshingDoesNotShowUpdatingBanner() {
        val host = HostProfile("v", "Contabo", "vps", username = "valentin", keySecretId = "key")
        val state = AppUiState(
            hosts = listOf(host),
            sessions = listOf(TmuxSession(host.id, "alpha", 1, 0, 0)),
            signedIn = true,
            refreshing = true,
            updatingHostIds = setOf(host.id),
        )
        compose.setContent {
            MaterialTheme {
                SessionsScreen(
                    state = state, signedIn = true, onSignIn = {}, onSignOut = {},
                    onOpen = {}, onOpenTerminal = {}, onArchive = {}, onArchiveUntil = { _, _ -> },
                    onRestore = {}, onDissolve = {}, onEnd = {}, onRestoreDissolved = {}, onForgetDissolved = {},
                    onMarkUnread = {}, onToggleArchived = {}, onSelectSessionBucket = {},
                    onSelectWorkspace = {}, onSwitchWorkspace = {}, onMoveWorkspaceSplit = { _, _ -> },
                    onResetWorkspaceSplitOrder = {}, onCreateWorkspace = { null },
                    onMoveSession = { _, _ -> }, onRenameSession = { _, _ -> null },
                    onSetFavorite = { _, _ -> }, onRefresh = {}, onSelectSection = {}, onForgetSession = {},
                    onStartNewSession = {}, onSetNewestSessionsAtBottom = {}, onSetAllSplitOnRight = {},
                    appTheme = AppTheme.SYSTEM, onSetAppTheme = {}, onClearSessionActionError = {},
                    readerDefaultFontScale = 1f, onSetReaderDefaultFontScale = {}, crashReport = null,
                    onClearCrashReport = {}, onMapAccountHost = { _, _ -> }, nowMillis = { 0L },
                )
            }
        }
        compose.onNodeWithText("alpha").assertIsDisplayed()
        compose.onNodeWithText("Updating", substring = true).assertDoesNotExist()
    }
}
