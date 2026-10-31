package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import dev.multiprompt.companion.AppUiState
import dev.multiprompt.companion.account.AccountLayout
import dev.multiprompt.companion.account.FREQUENT_SPLIT_ID
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
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class InboxConfirmationTest {
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

    private val dissolved = mutableListOf<TmuxSession>()
    private val ended = mutableListOf<TmuxSession>()

    private fun render() {
        compose.setContent {
            MaterialTheme {
                SessionsScreen(
                    state = state.value, signedIn = true, onSignIn = {}, onSignOut = {},
                    onOpen = {}, onOpenTerminal = {}, onArchive = {}, onArchiveUntil = { _, _ -> },
                    onRestore = {}, onDissolve = { dissolved.add(it) }, onEnd = { ended.add(it) }, onRestoreDissolved = {}, onForgetDissolved = {},
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
    }

    private fun checkConfirmation(menu: String, title: String, warning: String, confirm: String, archive: Boolean) {
        render()
        repeat(2) { attempt ->
            compose.onNodeWithText("gamma").performTouchInput { longClick() }
            compose.onNodeWithText(menu).performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithText(title).assertIsDisplayed()
            compose.onNodeWithText(warning).assertIsDisplayed()
            compose.onNodeWithText(confirm).performScrollTo().assertIsDisplayed()
            if (attempt == 0) {
                compose.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed().performClick()
                compose.runOnIdle { assertTrue(dissolved.isEmpty()); assertTrue(ended.isEmpty()) }
            } else {
                compose.onNodeWithText(confirm).performScrollTo().performClick()
                compose.runOnIdle {
                    val expected = state.value.inboxSessions.single { it.name == "gamma" }
                    assertEquals(if (archive) listOf(expected) else emptyList<TmuxSession>(), dissolved)
                    assertEquals(if (archive) emptyList<TmuxSession>() else listOf(expected), ended)
                }
            }
            compose.onNodeWithText(title).assertDoesNotExist()
        }
    }

    @Test fun archiveConfirmationTargetsGamma() {
        checkConfirmation(
            "Archive session", "Archive gamma?",
            "This ends its tmux session but saves the name and resume command so it can be restored later.",
            "Archive", true,
        )
    }

    @Test fun endConfirmationTargetsGamma() {
        checkConfirmation(
            "End session", "End gamma completely?",
            "This kills the coding agent and tmux session without saving a restore record.",
            "End session", false,
        )
    }
}
