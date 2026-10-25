package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import dev.multiprompt.companion.AppUiState
import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
class UnreadCountBadgeTest {
    @get:Rule
    val compose = createComposeRule()

    private val material3 = mutableStateOf(true)
    private val session = TmuxSession("host", "claude-reader", 1, 0, 100, preview = "❯")
    private val key = SessionReadStore.key(session.hostId, session.name)
    private val state = mutableStateOf(AppUiState(
        hosts = listOf(HostProfile("host", "VPS", "example.com", username = "user", keySecretId = "key")),
        sessions = listOf(session),
        unreadSessionKeys = setOf(key),
        unreadCounts = mapOf(key to 3),
    ))

    private fun render() {
        compose.setContent {
            SessionsScreen(
                state = state.value, signedIn = true, onSignIn = {}, onSignOut = {},
                onOpen = {}, onOpenTerminal = {}, onArchive = {}, onArchiveUntil = { _, _ -> },
                onRestore = {}, onDissolve = {}, onEnd = {}, onRestoreDissolved = {}, onForgetDissolved = {},
                onMarkUnread = {}, onToggleArchived = {}, onSelectSessionBucket = {}, onSelectWorkspace = {},
                onSwitchWorkspace = {}, onMoveWorkspaceSplit = { _, _ -> }, onResetWorkspaceSplitOrder = {},
                onCreateWorkspace = { null }, onMoveSession = { _, _ -> }, onRenameSession = { _, _ -> null },
                onSetFavorite = { _, _ -> }, onRefresh = {}, onSelectSection = {}, onForgetSession = {},
                onStartNewSession = {}, onSetNewestSessionsAtBottom = {}, onSetAllSplitOnRight = {},
                appTheme = AppTheme.DARK, onSetAppTheme = {}, onClearSessionActionError = {},
                readerDefaultFontScale = 1f, onSetReaderDefaultFontScale = {}, crashReport = null,
                onClearCrashReport = {}, onAddHost = {}, material3 = material3.value,
            )
        }
    }

    @Test
    fun m3RowShowsCountAndCappedLabel() {
        render()
        compose.onNodeWithText("3", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("3 unread messages", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(unreadCounts = mapOf(key to 150)) }
        compose.onNodeWithText("99+", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("150 unread messages", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("3", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun unreadWithoutObservedTurnsShowsOneAndReadHidesBadge() {
        state.value = state.value.copy(unreadCounts = emptyMap())
        render()
        compose.onNodeWithText("1", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(unreadSessionKeys = emptySet()) }
        compose.onNodeWithText("1", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("1 unread messages", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun classicRowShowsSameNumericBadge() {
        render()
        compose.onNodeWithText("3", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { material3.value = false }
        compose.onNodeWithText(session.displayName).assertIsDisplayed()
        compose.onNodeWithText("3", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("3 unread messages", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(unreadCounts = mapOf(key to 150)) }
        compose.onNodeWithText("99+", useUnmergedTree = true).assertIsDisplayed()
    }
}
