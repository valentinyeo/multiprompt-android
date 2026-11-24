package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import dev.multiprompt.companion.AppUiState
import dev.multiprompt.companion.account.AccountLayout
import dev.multiprompt.companion.account.AccountTab
import dev.multiprompt.companion.data.PinnedChats
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import dev.multiprompt.companion.reader.ChatHistoryMessage
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(InternalComposeTracingApi::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatListSnapshotTest {
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(scheduler))
    private val compositions = mutableMapOf<String, Int>()
    private val listState = LazyListState()
    private val host = HostProfile("host-1", "Hetzner", "vps", username = "valentin", keySecretId = "key")

    @Before
    fun countProductionRows() {
        Composer.setTracer(object : CompositionTracer {
            override fun isTraceInProgress() = true
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
                for (row in listOf("HistoryBubble", "SessionCard")) {
                    if (info.contains(".$row (")) compositions[row] = (compositions[row] ?: 0) + 1
                }
            }
            override fun traceEventEnd() = Unit
        })
    }

    @After
    fun stopCounting() {
        Composer.setTracer(null)
    }

    private fun measures(): Int = LazyListState::class.java.getDeclaredField("numMeasurePasses").let {
        it.isAccessible = true
        it.getInt(listState)
    }

    private fun report(row: String, recompositions: Int, remeasures: Int) {
        val line = "CHAT_LIST_SNAPSHOTS row=$row snapshots=50 recompositions=$recompositions remeasures=$remeasures"
        println(line)
        runCatching {
            val output = File(System.getProperty("user.dir"), "build")
            output.mkdirs()
            File(output, "chat-list-snapshots.txt").appendText("$line\n")
        }
    }

    private fun assertQuiet(row: String, update: (Int) -> Unit) {
        compose.waitForIdle()
        var beforeRows = 0
        var beforeMeasures = 0
        var beforePosition = 0 to 0
        compose.runOnIdle {
            beforeRows = compositions[row] ?: 0
            assertTrue("Production $row tracing must be active", beforeRows > 0)
            beforeMeasures = measures()
            assertTrue("The real lazy list must have measured", beforeMeasures > 0)
            beforePosition = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
        repeat(50) { snapshot ->
            compose.runOnIdle { update(snapshot) }
            compose.waitForIdle()
        }
        compose.runOnIdle {
            val rows = (compositions[row] ?: 0) - beforeRows
            val remeasures = measures() - beforeMeasures
            report(row, rows, remeasures)
            assertEquals("Unchanged rows must not recompose", 0, rows)
            assertEquals("Unchanged snapshots must not request lazy-list remeasure/scroll", 0, remeasures)
            assertEquals(beforePosition, listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset)
        }
    }

    @Test
    fun chatSnapshotsAtBottomDoNotRecomposeRowsOrRemeasure() = chatSnapshots(scrolled = false)

    @Test
    fun chatSnapshotsWhileReadingOlderMessagesStayQuiet() = chatSnapshots(scrolled = true)

    private fun chatSnapshots(scrolled: Boolean) {
        val messages = mutableStateOf((0 until 400).map { index ->
            ChatHistoryMessage("message-$index", if (index % 2 == 0) "user" else "assistant",
                if (index % 2 == 0) "Use /poteto-mode to inspect chat $index" else "Reply $index\n\nThe **reader** keeps its place.\n\n- Check the list\n- Keep scrolling",
                "", index * 100L, (index + 1) * 100L)
        }, neverEqualPolicy())
        val snapshot = mutableStateOf(0)
        var observed = -1
        compose.setContent {
            MaterialTheme {
                val current = snapshot.value
                Box(Modifier.height(520.dp)) {
                    ChatHistoryList(messages.value, listState = listState, onFindCount = { observed = current })
                }
            }
        }
        if (scrolled) compose.onNodeWithTag("chat-history").performScrollToIndex(200)
        assertQuiet("HistoryBubble") { index ->
            messages.value = messages.value.map { it.copy() }
            snapshot.value = index + 1
        }
        compose.runOnIdle { assertEquals("All 50 updates reached the production list", 50, observed) }
        var beforeChange = 0
        compose.runOnIdle {
            beforeChange = compositions["HistoryBubble"] ?: 0
            val visibleKey = listState.layoutInfo.visibleItemsInfo.first().key
            messages.value = messages.value.map { if (it.key == visibleKey) it.copy(text = "Changed reply") else it }
        }
        compose.onNodeWithText("Changed reply").assertIsDisplayed()
        compose.runOnIdle { assertTrue("A changed chat row must compose", (compositions["HistoryBubble"] ?: 0) > beforeChange) }
    }

    @Test
    fun inboxSnapshotsDoNotRecomposeRowsOrRemeasure() = inboxSnapshots(grouped = false)

    @Test
    fun pinnedGroupedInboxSnapshotsStayQuiet() = inboxSnapshots(grouped = true)

    private fun inboxSnapshots(grouped: Boolean) {
        val state = mutableStateOf(AppUiState(
            hosts = listOf(host),
            sessions = (0 until 80).map { index ->
                TmuxSession(host.id, "cl-project-$index", 1, 0, 1_800_000_000L - index,
                    workingDirectory = "/home/valentin/projects/project-$index", paneCommand = "claude",
                    preview = "● Reply $index is ready.\n\n❯ \n  ? for shortcuts", replyWaiting = false)
            },
            newestSessionsAtBottom = false,
            signedIn = grouped,
            accountHosts = if (grouped) mapOf(0 to host) else emptyMap(),
            accountLayout = if (grouped) AccountLayout(revision = 1, workspaces = listOf("Other"), tabs = (0 until 80).map { index ->
                AccountTab("cl-project-$index", "cl-project-$index", 0, "Other", group = if (index < 40) "Projects" else "Runners")
            }) else null,
            pinnedSessionKeys = if (grouped) listOf(PinnedChats.key(host.id, "cl-project-0")) else emptyList(),
        ), neverEqualPolicy())
        var observed = 0
        compose.setContent {
            MaterialTheme {
                SessionsScreen(
                    state = state.value,
                    signedIn = grouped,
                    onSignIn = {}, onSignOut = {}, onOpen = {}, onOpenTerminal = {},
                    onArchive = {}, onArchiveUntil = { _, _ -> }, onRestore = {}, onDissolve = {}, onEnd = {},
                    onRestoreDissolved = {}, onForgetDissolved = {}, onMarkUnread = {}, onToggleArchived = {},
                    onSelectSessionBucket = {}, onSelectWorkspace = {}, onSwitchWorkspace = {},
                    onMoveWorkspaceSplit = { _, _ -> }, onResetWorkspaceSplitOrder = {}, onCreateWorkspace = { null },
                    onMoveSession = { _, _ -> }, onRenameSession = { _, _ -> null }, onSetFavorite = { _, _ -> },
                    onRefresh = {}, onSelectSection = {}, onForgetSession = {}, onStartNewSession = {},
                    onSetNewestSessionsAtBottom = {}, onSetAllSplitOnRight = {}, appTheme = AppTheme.DARK,
                    onSetAppTheme = {}, onClearSessionActionError = {}, readerDefaultFontScale = 1f,
                    onSetReaderDefaultFontScale = {}, crashReport = null, onClearCrashReport = {},
                    onMapAccountHost = { _, _ -> }, sessionListState = listState,
                )
                androidx.compose.runtime.SideEffect { observed++ }
            }
        }
        assertQuiet("SessionCard") { index ->
            state.value = state.value.copy(
                sessions = state.value.sessions.map { it.copy() },
                unreadSessionKeys = state.value.unreadSessionKeys.toMutableSet(),
                unreadCounts = state.value.unreadCounts.toMutableMap(),
                pinnedSessionKeys = state.value.pinnedSessionKeys.toMutableList(),
                accountLayout = state.value.accountLayout?.let { it.copy(tabs = it.tabs.map { tab -> tab.copy() }) },
                accountSyncing = index % 2 == 0,
            )
        }
        compose.runOnIdle { assertTrue("Snapshot updates must actually compose", observed >= 51) }
        var beforeChange = 0
        compose.runOnIdle {
            beforeChange = compositions["SessionCard"] ?: 0
            state.value = state.value.copy(
                sessions = state.value.sessions.mapIndexed { index, session ->
                    if (index == 0) session.copy(accountName = "Changed chat") else session
                },
                accountLayout = state.value.accountLayout?.let { layout ->
                    layout.copy(tabs = layout.tabs.mapIndexed { index, tab -> if (index == 0) tab.copy(name = "Changed chat") else tab })
                },
            )
        }
        compose.onNodeWithText("Changed chat").assertIsDisplayed()
        compose.runOnIdle { assertTrue("A changed inbox row must compose", (compositions["SessionCard"] ?: 0) > beforeChange) }
    }
}
