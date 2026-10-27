package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.multiprompt.companion.AppUiState
import dev.multiprompt.companion.account.AccountLayout
import dev.multiprompt.companion.account.FAVORITES_SPLIT_ID
import dev.multiprompt.companion.account.FREQUENT_SPLIT_ID
import dev.multiprompt.companion.account.inboxSplitIds
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
class FrequentInboxTest {
    @get:Rule val compose = createComposeRule()
    private val fixture = JSONObject(javaClass.getResource("/igsh-415-frequent.json")!!.readText())
    private val layout = AccountLayout.parse(fixture.getJSONObject("layout"))
    private val host = HostProfile("v", "Hetzner", "vps", username = "valentin", keySecretId = "key")
    private val state = mutableStateOf(AppUiState(
        hosts = listOf(host),
        sessions = layout.tabs.map { TmuxSession(host.id, it.session, 1, 0, 0) },
        signedIn = true,
        accountLayout = layout,
        accountHosts = mapOf(0 to host),
        selectedWorkspaceId = FREQUENT_SPLIT_ID,
        newestSessionsAtBottom = true,
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
                    onClearCrashReport = {}, onHostMapping = {}, nowMillis = { fixture.getLong("now") },
                )
            }
        }
    }

    private fun assertGammaIsBottomRow() {
        compose.onNodeWithText("alpha").assertIsDisplayed()
        compose.onNodeWithText("gamma").assertIsDisplayed()
        compose.onNodeWithText("beta").assertIsDisplayed()
        compose.onNodeWithText("delta").assertDoesNotExist()
        compose.onNodeWithText("epsilon").assertDoesNotExist()
        compose.onNodeWithText("runner").assertDoesNotExist()
        val beta = compose.onNodeWithText("beta").fetchSemanticsNode().boundsInRoot
        val alpha = compose.onNodeWithText("alpha").fetchSemanticsNode().boundsInRoot
        val gamma = compose.onNodeWithText("gamma").fetchSemanticsNode().boundsInRoot
        val split = compose.onNodeWithText("Frequent").fetchSemanticsNode().boundsInRoot
        assertTrue("alpha must be below beta on screen", alpha.top >= beta.bottom)
        assertTrue("gamma must be below alpha on screen", gamma.top >= alpha.bottom)
        assertTrue("gamma must be the session row nearest the split bar", split.top - gamma.bottom < split.top - alpha.bottom)
        assertTrue("split bar must be below gamma", split.top >= gamma.bottom)
        assertTrue("gamma must be anchored next to the split bar, not at the top of an empty list",
            split.top - gamma.bottom < gamma.top - alpha.top)
    }

    @Test fun frequentIsFirstChipAndFixtureGammaIsBottomWithReverseLayout() {
        render()
        assertGammaIsBottomRow()
        val frequent = compose.onNodeWithText("Frequent").fetchSemanticsNode().boundsInRoot
        val all = compose.onNodeWithText("All").fetchSemanticsNode().boundsInRoot
        val favorites = compose.onNodeWithText("Favorites").fetchSemanticsNode().boundsInRoot
        assertTrue("Frequent must be the first chip", frequent.left < all.left && all.left < favorites.left)
    }

    @Test fun settingOffPlacesNewestAtTopLikeOtherSplits() {
        state.value = state.value.copy(newestSessionsAtBottom = false)
        render()
        val gamma = compose.onNodeWithText("gamma").fetchSemanticsNode().boundsInRoot
        val alpha = compose.onNodeWithText("alpha").fetchSemanticsNode().boundsInRoot
        val beta = compose.onNodeWithText("beta").fetchSemanticsNode().boundsInRoot
        assertTrue(gamma.bottom <= alpha.top && alpha.bottom <= beta.top)
    }

    @Test fun longFrequentListInitiallyShowsNewestAtBottomWithSettingOn() {
        val extended = layout.copy(tabs = layout.tabs + (1..30).map { index ->
            layout.tabs.first().copy(name = "older_$index", session = "older_$index", input = fixture.getLong("now") - 20_000_000 - index)
        })
        state.value = state.value.copy(
            newestSessionsAtBottom = true,
            accountLayout = extended,
            sessions = extended.tabs.map { TmuxSession(host.id, it.session, 1, 0, 0) },
        )
        render()
        assertGammaIsBottomRow()
        compose.onNodeWithText("older_30").assertDoesNotExist()
    }

    @Test fun selectingAllOrFavoritesStillSelectsSameIdAfterSplitReorder() {
        render()
        for ((label, id) in listOf("All" to null, "Favorites" to FAVORITES_SPLIT_ID, "Frequent" to FREQUENT_SPLIT_ID)) {
            compose.onNodeWithText(label).performClick()
            compose.runOnIdle { assertEquals(id, state.value.selectedWorkspaceId) }
        }
        val workspaces = listOf(null, "Other", "Product")
        for (right in listOf(false, true)) {
            val oldOrder = (if (right) workspaces.asReversed() else workspaces).flatMap {
                if (it == null) listOf(null, FAVORITES_SPLIT_ID, FREQUENT_SPLIT_ID) else listOf(it)
            }
            val newOrder = inboxSplitIds(workspaces, right)
            for (selected in oldOrder) {
                val selectedById = state.value.copy(selectedWorkspaceId = selected)
                assertEquals(selected, newOrder[newOrder.indexOf(selectedById.selectedWorkspaceId)])
            }
        }
    }
}
