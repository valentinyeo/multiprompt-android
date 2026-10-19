package dev.multiprompt.companion.ui

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import dev.multiprompt.companion.account.AccountLayout
import dev.multiprompt.companion.account.AccountLayoutStore
import dev.multiprompt.companion.account.FAVORITES_SPLIT_ID
import dev.multiprompt.companion.account.FREQUENT_SPLIT_ID
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionListSettingsTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: SessionActivityFixture
    private lateinit var back: OnBackPressedDispatcher

    @After fun close() {
        if (::fixture.isInitialized) fixture.close()
    }

    private fun render() {
        fixture = SessionActivityFixture()
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            val state = fixture.model.state.collectAsState().value
            SessionsScreen(
                state = state, signedIn = true, onSignIn = {}, onSignOut = {},
                onOpen = {}, onOpenTerminal = {}, onArchive = {}, onArchiveUntil = { _, _ -> },
                onRestore = {}, onDissolve = {}, onEnd = {}, onRestoreDissolved = {}, onForgetDissolved = {},
                onMarkUnread = {}, onToggleArchived = {}, onSelectSessionBucket = {},
                onSelectWorkspace = fixture.model::selectWorkspace,
                onSwitchWorkspace = fixture.model::openAdjacentWorkspace,
                onMoveWorkspaceSplit = { _, _ -> }, onResetWorkspaceSplitOrder = {},
                onCreateWorkspace = { null }, onMoveSession = { _, _ -> }, onRenameSession = { _, _ -> null },
                onSetFavorite = { tab, value -> fixture.model.editAccountLayout { it.setFavorite(tab.identity, value) } },
                onRefresh = {}, onSelectSection = {}, onForgetSession = {}, onStartNewSession = {},
                onSetNewestSessionsAtBottom = fixture.model::setNewestSessionsAtBottom,
                onSetAllSplitOnRight = fixture.model::setAllSplitOnRight,
                onSetSessionListSetting = fixture.model::setSessionListSetting,
                appTheme = state.appTheme, onSetAppTheme = fixture.model::setAppTheme,
                onClearSessionActionError = {}, readerDefaultFontScale = state.readerDefaultFontScale,
                onSetReaderDefaultFontScale = fixture.model::setReaderDefaultFontScale,
                crashReport = null, onClearCrashReport = {}, onAddHost = {},
            )
        }
    }

    private fun openSettings() {
        compose.onNodeWithContentDescription("Session list actions").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Session list settings").assertIsDisplayed()
        compose.onNodeWithText("Inbox").assertDoesNotExist()
    }

    private fun closeSettings() {
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithText("Session list settings").assertDoesNotExist()
        compose.onNodeWithText("Inbox").assertIsDisplayed()
    }

    @Test fun allChoiceLabelsStayOnOneLineAt390DpAndChoicesStillWork() {
        render()
        openSettings()
        for (label in listOf("System", "Dark", "Sunlight", "Small", "Normal", "Large")) {
            val node = compose.onNodeWithText(label)
            node.performScrollTo().assertIsDisplayed()
            val results = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertEquals(label, 1, results.single().lineCount)
            assertTrue("$label must fit its text bounds", results.single().getLineRight(0) - results.single().getLineLeft(0) <= results.single().size.width)
            node.performClick()
        }
        compose.runOnIdle {
            assertEquals(AppTheme.SUNLIGHT, fixture.model.state.value.appTheme)
            assertEquals(1.15f, fixture.model.state.value.readerDefaultFontScale)
        }
        closeSettings()
        openSettings()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Session list settings").assertDoesNotExist()
    }

    private fun toggleSplit(key: String, label: String, split: String, id: String?) {
        render()
        compose.onNodeWithText(split).performClick()
        openSettings()
        compose.onNodeWithContentDescription(label).performClick()
        compose.runOnIdle {
            val state = fixture.model.state.value
            assertEquals(false, state.accountLayout!!.settings!![key])
            assertTrue(state.accountPending)
            assertEquals(state.visibleWorkspaceSplitIds.first(), state.selectedWorkspaceId)
            assertNotEquals(id, state.selectedWorkspaceId)
            val saved = AccountLayoutStore(fixture.app).load("test-account")!!
            assertEquals(false, saved.edited.settings!![key])
        }
        if (key == "frequent") compose.onNodeWithContentDescription("Frequent as first tab").assertIsNotEnabled()
        closeSettings()
        compose.onNodeWithText(split).assertDoesNotExist()
        openSettings()
        compose.onNodeWithContentDescription(label).performClick()
        closeSettings()
        compose.onNodeWithText(split).assertIsDisplayed()
        compose.runOnIdle { assertEquals(true, fixture.model.state.value.accountLayout!!.settings!![key]) }
    }

    @Test fun frequentSwitchHidesAndRestoresFrequent() = toggleSplit("frequent", "Show Frequent split", "Frequent", FREQUENT_SPLIT_ID)

    @Test fun favoritesSwitchHidesAndRestoresFavorites() = toggleSplit("favorites", "Show Favorites split", "Favorites", FAVORITES_SPLIT_ID)

    @Test fun allSwitchHidesAndRestoresAll() = toggleSplit("all", "Show All split", "All", null)

    @Test fun frequentFirstOffMovesFrequentImmediatelyAfterFavoritesOrAll() {
        render()
        openSettings()
        compose.onNodeWithContentDescription("Frequent as first tab").performClick()
        closeSettings()
        val favorites = compose.onNodeWithText("Favorites").fetchSemanticsNode().boundsInRoot
        val frequent = compose.onNodeWithText("Frequent").fetchSemanticsNode().boundsInRoot
        assertTrue(frequent.left >= favorites.right)
        compose.runOnIdle {
            val splits = fixture.model.state.value.visibleWorkspaceSplitIds
            assertEquals(splits.indexOf(FAVORITES_SPLIT_ID) + 1, splits.indexOf(FREQUENT_SPLIT_ID))
            fixture.model.selectWorkspace(FAVORITES_SPLIT_ID)
            fixture.model.openAdjacentWorkspace(1)
            assertEquals(FREQUENT_SPLIT_ID, fixture.model.state.value.selectedWorkspaceId)
        }
        openSettings()
        compose.onNodeWithContentDescription("Show Favorites split").performClick()
        closeSettings()
        val all = compose.onNodeWithText("All").fetchSemanticsNode().boundsInRoot
        assertTrue(compose.onNodeWithText("Frequent").fetchSemanticsNode().boundsInRoot.left >= all.right)
    }

    @Test fun hidingAllBuiltInSplitsKeepsAllWhenNoWorkspaceRemainsAndNavigationSkipsHiddenSplits() {
        render()
        compose.runOnIdle { fixture.model.editAccountLayout { AccountLayout(it.revision, settings = it.settings) } }
        openSettings()
        for (label in listOf("Show Frequent split", "Show Favorites split", "Show All split")) {
            compose.onNodeWithContentDescription(label).performClick()
        }
        closeSettings()
        compose.onNodeWithText("All").assertIsDisplayed()
        compose.onNodeWithText("Frequent").assertDoesNotExist()
        compose.onNodeWithText("Favorites").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf<String?>(null), fixture.model.state.value.visibleWorkspaceSplitIds)
            fixture.model.openAdjacentWorkspace(1)
            assertNull(fixture.model.state.value.selectedWorkspaceId)
            fixture.model.selectWorkspace(FREQUENT_SPLIT_ID)
            assertNull(fixture.model.state.value.selectedWorkspaceId)
        }
    }
}
