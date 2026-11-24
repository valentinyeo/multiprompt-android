package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.multiprompt.companion.AppUiState
import dev.multiprompt.companion.SessionBucket
import dev.multiprompt.companion.data.PinnedChats
import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
class PinnedChatsUiTest {
    @get:Rule val compose = createComposeRule()
    private val host = HostProfile("host-1", "Hetzner", "vps", username = "valentin", keySecretId = "key")
    private val first = TmuxSession(host.id, "alpha", 1, 0, 300)
    private val second = TmuxSession(host.id, "beta", 1, 0, 200)
    private val state = mutableStateOf(
        AppUiState(
            hosts = listOf(host),
            sessions = listOf(first, second),
            sessionInteractionEpochSeconds = mapOf(
                SessionReadStore.key(host.id, "alpha") to 300L,
                SessionReadStore.key(host.id, "beta") to 200L,
            ),
            newestSessionsAtBottom = false,
        ),
    )

    private fun render() {
        compose.setContent {
            MaterialTheme {
                SessionsScreen(
                    state = state.value,
                    signedIn = false,
                    onSignIn = {},
                    onSignOut = {},
                    onOpen = {},
                    onOpenTerminal = {},
                    onArchive = {},
                    onArchiveUntil = { _, _ -> },
                    onRestore = {},
                    onDissolve = {},
                    onEnd = {},
                    onRestoreDissolved = {},
                    onForgetDissolved = {},
                    onMarkUnread = {},
                    onToggleArchived = {},
                    onSelectSessionBucket = {},
                    onSelectWorkspace = {},
                    onSwitchWorkspace = {},
                    onMoveWorkspaceSplit = { _, _ -> },
                    onResetWorkspaceSplitOrder = {},
                    onCreateWorkspace = { null },
                    onMoveSession = { _, _ -> },
                    onRenameSession = { _, _ -> null },
                    onSetFavorite = { _, _ -> },
                    onRefresh = {},
                    onSelectSection = {},
                    onForgetSession = {},
                    onStartNewSession = {},
                    onSetNewestSessionsAtBottom = {},
                    onSetAllSplitOnRight = {},
                    appTheme = AppTheme.SYSTEM,
                    onSetAppTheme = {},
                    onClearSessionActionError = {},
                    readerDefaultFontScale = 1f,
                    onSetReaderDefaultFontScale = {},
                    crashReport = null,
                    onClearCrashReport = {},
                    onMapAccountHost = { _, _ -> },
                    onSetPinned = { session, pinned ->
                        val key = PinnedChats.key(session)
                        val updated = if (pinned) PinnedChats.pin(state.value.pinnedSessionKeys, key)
                        else PinnedChats.unpin(state.value.pinnedSessionKeys, key)
                        state.value = state.value.copy(pinnedSessionKeys = updated)
                    },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun topY(label: String): Float = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun pinAndUnpinFromChatMenuReorderRows() {
        render()
        assertTrue(topY("alpha") < topY("beta"))

        compose.onNodeWithText("beta").performTouchInput { longClick() }
        compose.onNodeWithText(PinnedChats.PIN_LABEL).assertIsDisplayed()
        compose.onNodeWithText(PinnedChats.UNPIN_LABEL).assertDoesNotExist()
        compose.onNodeWithText(PinnedChats.PIN_LABEL).performClick()
        compose.waitForIdle()

        assertEquals(listOf(PinnedChats.key(second)), state.value.pinnedSessionKeys)
        assertTrue(topY("beta") < topY("alpha"))

        compose.onNodeWithText("beta").performTouchInput { longClick() }
        compose.onNodeWithText(PinnedChats.UNPIN_LABEL).assertIsDisplayed()
        compose.onNodeWithText(PinnedChats.PIN_LABEL).assertDoesNotExist()
        compose.onNodeWithText(PinnedChats.UNPIN_LABEL).performClick()
        compose.waitForIdle()

        assertEquals(emptyList<String>(), state.value.pinnedSessionKeys)
        assertTrue(topY("alpha") < topY("beta"))
    }

    @Test
    fun pinningInNewestAtBottomOrderPutsThePinnedRowAtTheVisualTop() {
        val many = listOf(first, second) + (1..20).map { index ->
            TmuxSession(host.id, "row-$index", 1, 0, 100L - index)
        }
        state.value = state.value.copy(
            sessions = many,
            newestSessionsAtBottom = true,
        )
        render()
        compose.onNodeWithText("alpha").assertIsDisplayed()
        compose.onNodeWithText("beta").assertIsDisplayed()
        assertTrue(topY("beta") < topY("alpha"))

        compose.onNodeWithText("beta").performTouchInput { longClick() }
        compose.onNodeWithText(PinnedChats.PIN_LABEL).performClick()
        compose.waitForIdle()

        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(many.size - 1)
        compose.waitForIdle()

        compose.onNodeWithText("beta").assertIsDisplayed()
        compose.onNodeWithText("row-20").assertIsDisplayed()
        assertTrue(topY("beta") < topY("row-20"))
        assertTrue(topY("row-20") < topY("row-19"))
        compose.onNodeWithText("alpha").assertDoesNotExist()

        compose.onNodeWithText("beta").performTouchInput { longClick() }
        compose.onNodeWithText(PinnedChats.UNPIN_LABEL).performClick()
        compose.waitForIdle()

        assertEquals(emptyList<String>(), state.value.pinnedSessionKeys)
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.waitForIdle()

        compose.onNodeWithText("alpha").assertIsDisplayed()
        assertTrue(topY("beta") < topY("alpha"))
        compose.onNodeWithText("row-20").assertDoesNotExist()
    }

    @Test
    fun waitingBucketRowsCanBePinned() {
        state.value = state.value.copy(
            sessionBucket = SessionBucket.WAITING,
            archivedSessionKeys = setOf(PinnedChats.key(second)),
        )
        render()

        compose.onNodeWithText("beta").performTouchInput { longClick() }
        compose.onNodeWithText(PinnedChats.PIN_LABEL).performClick()
        compose.waitForIdle()

        assertEquals(listOf(PinnedChats.key(second)), state.value.pinnedSessionKeys)
    }
}
