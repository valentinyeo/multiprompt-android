package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.multiprompt.companion.AppUiState
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import dev.multiprompt.companion.room.RoomEntry
import dev.multiprompt.companion.room.RoomMemberEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class QuietInboxTest {
    @get:Rule val compose = createComposeRule()

    private fun render(state: AppUiState) {
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
    }

    @Test fun refreshingDoesNotShowUpdatingBanner() {
        val host = HostProfile("v", "Contabo", "vps", username = "valentin", keySecretId = "key")
        render(AppUiState(
            hosts = listOf(host),
            sessions = listOf(TmuxSession(host.id, "alpha", 1, 0, 0)),
            signedIn = true,
            refreshing = true,
            updatingHostIds = setOf(host.id),
        ))
        compose.onNodeWithText("alpha").assertIsDisplayed()
        compose.onNodeWithText("Updating", substring = true).assertDoesNotExist()
    }

    @Test fun hostThatRejectsThisPhoneShowsNoRedLine() {
        val home = HostProfile("v", "Contabo", "vps", username = "valentin", keySecretId = "key")
        val cro = HostProfile("c", "Inne CRO", "cro", username = "inne", keySecretId = "key")
        render(AppUiState(
            hosts = listOf(home, cro),
            sessions = listOf(TmuxSession(home.id, "alpha", 1, 0, 0)),
            signedIn = true,
            hostErrors = mapOf(cro.id to dev.multiprompt.companion.ssh.SshProblem.keyRejected(cro.label)),
        ))
        compose.onNodeWithText("alpha").assertIsDisplayed()
        compose.onNodeWithText("does not accept", substring = true).assertDoesNotExist()
    }

    @Test fun backgroundRefreshShowsNoSpinner() {
        val host = HostProfile("v", "Contabo", "vps", username = "valentin", keySecretId = "key")
        render(AppUiState(
            hosts = listOf(host),
            sessions = listOf(TmuxSession(host.id, "alpha", 1, 0, 0)),
            signedIn = true,
            refreshing = true,
        ))
        compose.onNodeWithText("alpha").assertIsDisplayed()
        compose.onAllNodes(androidx.compose.ui.test.hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate))
            .assertCountEquals(0)
    }

    @Test fun tappingARoomOpensItOverTheInbox() {
        val host = HostProfile("v", "Contabo", "vps", username = "valentin", keySecretId = "key")
        render(AppUiState(
            hosts = listOf(host),
            sessions = listOf(TmuxSession(host.id, "alpha", 1, 0, 0)),
            signedIn = true,
            rooms = listOf(RoomEntry("room-1", "Proof room", 1, listOf(RoomMemberEntry("m1", "helper", "Room Helper", "claude:sid")))),
        ))
        compose.onNodeWithText("Proof room").performClick()
        compose.onNodeWithText("Message Proof room").assertIsDisplayed()
        compose.onNodeWithText("alpha").assertDoesNotExist()
    }
}
