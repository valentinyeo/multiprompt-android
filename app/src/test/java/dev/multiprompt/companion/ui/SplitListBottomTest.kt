package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import dev.multiprompt.companion.AppUiState
import dev.multiprompt.companion.account.AccountLayout
import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp")
class SplitListBottomTest {
    @get:Rule val compose = createComposeRule()
    private val fixture = JSONObject(javaClass.getResource("/igsh-415-frequent.json")!!.readText())
    private val host = HostProfile("v", "Hetzner", "vps", username = "valentin", keySecretId = "key")
    private val short = AccountLayout.parse(fixture.getJSONObject("layout"))
    private val layout = short.copy(tabs = short.tabs + (1..30).map { index ->
        short.tabs.first().copy(name = "older_$index", session = "older_$index", input = fixture.getLong("now") - 20_000_000 - index)
    })
    private val rows = listOf("alpha", "beta", "gamma", "delta", "epsilon", "runner") + (1..30).map { "older_$it" }
    private val state = mutableStateOf(
        AppUiState(
            hosts = listOf(host),
            sessions = layout.tabs.map { TmuxSession(host.id, it.session, 1, 0, 0) },
            signedIn = true,
            accountLayout = layout,
            accountHosts = mapOf(0 to host),
            sessionWorkspaceIds = layout.tabs.associate { SessionReadStore.key(host.id, it.session) to "Other" },
            selectedWorkspaceId = null,
            newestSessionsAtBottom = true,
        )
    )

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
                    onClearCrashReport = {}, onMapAccountHost = { _, _ -> }, nowMillis = { fixture.getLong("now") },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun visibleRows(): List<Pair<String, Rect>> = rows.mapNotNull { label ->
        compose.onAllNodesWithText(label).fetchSemanticsNodes().firstOrNull()?.let { label to it.boundsInRoot }
    }

    private fun bottomRow(): String = visibleRows().maxBy { it.second.top }.first

    private fun topRow(): String = visibleRows().minBy { it.second.top }.first

    private fun scrollUpTheList() {
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(24)
        compose.waitForIdle()
    }

    private fun switchSplit() {
        compose.runOnIdle { state.value = state.value.copy(selectedWorkspaceId = "Other") }
        compose.waitForIdle()
    }

    @Test fun switchingSplitBringsTheNewestRowBackToTheBottom() {
        render()
        val newest = bottomRow()
        scrollUpTheList()
        assertNotEquals("The list must move up the split", newest, bottomRow())
        switchSplit()
        assertEquals(newest, bottomRow())
        val row = visibleRows().first { it.first == newest }.second
        val bar = compose.onNodeWithText("All").fetchSemanticsNode().boundsInRoot
        assertTrue("Newest row must sit next to the split bar", bar.top - row.bottom < row.height)
    }

    @Test fun aNewestAtTopSplitReturnsToTheNewestRowToo() {
        state.value = state.value.copy(newestSessionsAtBottom = false)
        render()
        val newest = topRow()
        scrollUpTheList()
        assertNotEquals(newest, topRow())
        switchSplit()
        assertEquals(newest, topRow())
    }
}
