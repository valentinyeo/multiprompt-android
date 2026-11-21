package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import dev.multiprompt.companion.AppUiState
import dev.multiprompt.companion.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp")
class InboxSplitPillSpacingTest {
    @get:Rule val compose = createComposeRule()
    private val state = mutableStateOf(AppUiState())

    private fun folders(count: Int) = (1..count).map {
        Workspace(id = "folder-$it", name = "Folder %02d".format(it), hostId = "h", remotePath = "/tmp")
    }

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
                    onClearCrashReport = {}, onMapAccountHost = { _, _ -> }, nowMillis = { 0L },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun left(label: String) = compose.onNodeWithText(label).getBoundsInRoot().left.value

    private fun right(label: String) = compose.onNodeWithText(label).getBoundsInRoot().right.value

    private fun screen() = compose.onRoot().getBoundsInRoot().right.value

    @Test fun pillsSitACloseGapFromTheLeftEdge() {
        render()
        assertEquals(Kit.ScreenEdge.value, left("All"), 1f)
        assertEquals(Kit.ControlGap.value, left("Favorites") - right("All"), 1f)
        assertEquals(Kit.ControlGap.value, left("Frequent") - right("Favorites"), 1f)
        assertTrue("Bar must fit three pills on a phone", right("Frequent") <= screen() - 12f)
    }

    @Test fun overflowingBarKeepsTheFirstPillAtTheLeftInset() {
        state.value = AppUiState(
            workspaceSplitIds = listOf(null) + folders(9).map { it.id },
            workspaces = folders(9),
            allSplitOnRight = false,
        )
        render()
        compose.onNodeWithText("All").assertIsDisplayed()
        assertEquals(Kit.ScreenEdge.value, left("All"), 1f)
        assertEquals(Kit.ControlGap.value, left("Favorites") - right("All"), 1f)
        compose.onNodeWithText("Folder 09").assertIsNotDisplayed()
    }

    @Test fun selectedOverflowingFolderStaysFullyVisibleInsideTheBarEdges() {
        val all = folders(9)
        state.value = AppUiState(
            workspaceSplitIds = listOf(null) + all.map { it.id },
            workspaces = all,
            selectedWorkspaceId = all.last().id,
        )
        render()
        compose.onNodeWithText("Folder 09").assertIsDisplayed()
        assertFullyVisible("Folder 09")
        state.value = state.value.copy(selectedWorkspaceId = all[4].id)
        compose.waitForIdle()
        compose.onNodeWithText("Folder 05").assertIsDisplayed()
        assertFullyVisible("Folder 05")
    }

    private fun assertFullyVisible(label: String) {
        val inset = Kit.ScreenEdge.value
        assertTrue("$label starts under the left edge", left(label) >= inset - 1f)
        assertTrue("$label runs under the right edge", right(label) <= screen() - inset + 1f)
    }
}
