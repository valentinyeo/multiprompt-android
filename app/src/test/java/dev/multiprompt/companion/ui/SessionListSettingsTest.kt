package dev.multiprompt.companion.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import dev.multiprompt.companion.account.AccountLayout
import dev.multiprompt.companion.account.AccountLayoutStore
import dev.multiprompt.companion.account.FAVORITES_SPLIT_ID
import dev.multiprompt.companion.account.FREQUENT_SPLIT_ID
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class, androidx.compose.ui.InternalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionListSettingsTest {
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(scheduler))
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
                crashReport = null, onClearCrashReport = {}, onMapAccountHost = { _, _ -> },
            )
        }
    }

    private fun openSettings() {
        compose.onNodeWithContentDescription("Session list actions").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Session list settings").assertIsDisplayed()
        compose.onNodeWithText("Inbox").assertDoesNotExist()
    }

    @Test fun settingsKeepsAccountAndUpdateActionsAfterRemovingConfigurationPages() {
        render()
        openSettings()
        compose.onNodeWithText("Switch / renew login").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sign out").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Host mapping").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Update").performScrollTo().assertIsDisplayed()
        closeSettings()
        compose.onNodeWithContentDescription("Session list actions").performClick()
        compose.onNodeWithText("Hosts").assertDoesNotExist()
        compose.onNodeWithText("Workspaces").assertDoesNotExist()
    }

    @Test fun signedInAccountErrorIsVisibleWithoutClearingCredentials() {
        render()
        compose.runOnIdle {
            assertTrue(fixture.model.syncAuth.hasSession())
            fixture.state.value = fixture.state.value.copy(signedIn = true, accountError = "Account unavailable; retry")
        }
        openSettings()
        compose.onNodeWithText("Account unavailable; retry").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Switch / renew login").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(fixture.model.state.value.signedIn)
            assertTrue(fixture.model.syncAuth.hasSession())
            assertEquals("test-account", fixture.model.syncAuth.accountId())
        }
    }

    private fun closeSettings() {
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithText("Session list settings").assertDoesNotExist()
        compose.onNodeWithText("Inbox").assertIsDisplayed()
    }

    private fun assertChoiceLabelFits(label: String) {
        val node = compose.onNodeWithText(label, useUnmergedTree = true)
        node.performScrollTo().assertIsDisplayed()
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        assertEquals(label, 1, layout.lineCount)
        assertFalse("$label must not overflow: ${layout.size}, paragraph width ${layout.multiParagraph.width}", layout.hasVisualOverflow)
        assertTrue("$label must fit its text bounds", layout.getLineLeft(0) >= 0 && layout.getLineRight(0) <= layout.size.width)
        val text = node.getUnclippedBoundsInRoot()
        val button = compose.onNode(hasText(label) and hasClickAction()).getUnclippedBoundsInRoot()
        assertTrue("$label must stay inside its button: $text vs $button",
            text.left >= button.left && text.top >= button.top && text.right <= button.right && text.bottom <= button.bottom)
    }

    private fun checkChoiceLabelsAndActions() {
        render()
        openSettings()
        for (label in listOf("System", "Dark", "Sunlight", "Small", "Normal", "Large")) {
            assertChoiceLabelFits(label)
            compose.onNodeWithText(label).performClick()
            assertChoiceLabelFits(label)
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

    @Test fun allChoiceLabelsStayOnOneLineAt390DpAndChoicesStillWork() = checkChoiceLabelsAndActions()

    @Test fun allChoiceLabelsFitAt390DpWithLargestSystemFont() {
        RuntimeEnvironment.setFontScale(2f)
        assertEquals(2f, RuntimeEnvironment.getFontScale())
        checkChoiceLabelsAndActions()
    }

    private fun screenshot(name: String) {
        val root = compose.onRoot()
        val view = (root.fetchSemanticsNode().root as ViewRootForTest).view.rootView
        val attachInfo = ReflectionHelpers.getField<Any>(view, "mAttachInfo")
        val hardwareAccelerated = view.isHardwareAccelerated
        ReflectionHelpers.setField(attachInfo, "mHardwareAccelerated", false)
        val redraw = CoroutineScope(UnconfinedTestDispatcher(scheduler)).launch {
            delay(16)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
            view.viewTreeObserver.dispatchOnDraw()
            shadowOf(Looper.getMainLooper()).idle()
        }
        val bitmap = try {
            root.captureToImage().asAndroidBitmap()
        } finally {
            redraw.cancel()
            ReflectionHelpers.setField(attachInfo, "mHardwareAccelerated", hardwareAccelerated)
        }
        val output = File("build/outputs/igsh-423/$name.png")
        checkNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(output.isFile && output.length() > 0)
    }

    @Test fun captureSettingsAt390Dp() {
        render()
        openSettings()
        screenshot("settings-390dp")
    }

    @Test fun captureSplitBarWithFrequentHidden() {
        render()
        openSettings()
        compose.onNodeWithContentDescription("Show Frequent split").performClick()
        closeSettings()
        compose.onNodeWithText("Frequent").assertDoesNotExist()
        compose.onNodeWithText("Favorites").assertIsDisplayed()
        compose.onNodeWithText("All").assertIsDisplayed()
        screenshot("split-bar-frequent-hidden")
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
