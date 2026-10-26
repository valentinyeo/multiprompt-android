package dev.multiprompt.companion.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.multiprompt.companion.MainActivity
import dev.multiprompt.companion.AppUiState
import dev.multiprompt.companion.SessionBucket
import dev.multiprompt.companion.account.AccountLayout
import dev.multiprompt.companion.account.FAVORITES_SPLIT_ID
import dev.multiprompt.companion.account.FREQUENT_SPLIT_ID
import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import dev.multiprompt.companion.model.Workspace
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SessionRowsTest {
    @get:Rule(order = 0) val notifications = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private var opened: String? = null
    private val host = HostProfile("host", "VPS", "example.com", username = "user", keySecretId = "key")
    private val sessions = List(6) { index ->
        TmuxSession("host", "claude-runner-$index", 1, 0, 1_799_999_880 - index * 60L,
            preview = "[CAVEMAN] Opus 5.5 medium software-factory $index")
    }
    private val layout = AccountLayout.parse(JSONObject("""{"schema":1,"revision":1,"workspaces":[{"name":"Android"}],"tabs":[${sessions.joinToString(",") {
        """{"kind":"tmux","name":"${it.name}","tmux_session":"${it.name}","tmux_host":0,"workspace":"Android","favorite":true,"input":1799999940000}"""
    }}]}"""))
    private val workspace = Workspace("work", "Android", "host", "/home/user/android")
    private val state = mutableStateOf(AppUiState(
        hosts = listOf(host), sessions = sessions, signedIn = true,
        workspaces = listOf(workspace), accountLayout = layout, accountHosts = mapOf(0 to host),
        sessionWorkspaceIds = sessions.associate { SessionReadStore.key(it.hostId, it.name) to workspace.id },
        unreadSessionKeys = sessions.map { SessionReadStore.key(it.hostId, it.name) }.toSet(),
        unreadCounts = sessions.associate { SessionReadStore.key(it.hostId, it.name) to 3 },
        newestSessionsAtBottom = false,
    ))

    private fun render() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
            SessionsScreen(
                state = state.value, signedIn = true, onSignIn = {}, onSignOut = {},
                onOpen = { opened = it.name }, onOpenTerminal = {}, onArchive = {}, onArchiveUntil = { _, _ -> },
                onRestore = {}, onDissolve = {}, onEnd = {}, onRestoreDissolved = {}, onForgetDissolved = {},
                onMarkUnread = {}, onToggleArchived = {}, onSelectSessionBucket = {}, onSelectWorkspace = {},
                onSwitchWorkspace = {}, onMoveWorkspaceSplit = { _, _ -> }, onResetWorkspaceSplitOrder = {},
                onCreateWorkspace = { null }, onMoveSession = { _, _ -> }, onRenameSession = { _, _ -> null },
                onSetFavorite = { _, _ -> }, onRefresh = {}, onSelectSection = {}, onForgetSession = {},
                onStartNewSession = {}, onSetNewestSessionsAtBottom = {}, onSetAllSplitOnRight = {},
                appTheme = AppTheme.DARK, onSetAppTheme = {}, onClearSessionActionError = {},
                readerDefaultFontScale = 1f, onSetReaderDefaultFontScale = {}, crashReport = null,
                onClearCrashReport = {}, onAddHost = {}, material3 = false, nowMillis = { 1_800_000_000_000 },
            )
            }
        }
    }

    @Test fun rowsStayCompactAcrossSplitsAndStillOpen() {
        render()
        val output = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "session-rows").apply { mkdirs() }
        val problems = mutableListOf<String>()
        val report = mutableListOf<String>()
        for ((label, split) in listOf("all" to null, "favorites" to FAVORITES_SPLIT_ID, "frequent" to FREQUENT_SPLIT_ID, "workspace" to workspace.id, "waiting" to null)) {
            compose.runOnIdle {
                state.value = state.value.copy(selectedWorkspaceId = split,
                    sessionBucket = if (label == "waiting") SessionBucket.WAITING else SessionBucket.OPEN,
                    archivedSessionKeys = if (label == "waiting") sessions.map { SessionReadStore.key(it.hostId, it.name) }.toSet() else emptySet())
            }
            compose.waitForIdle()
            val first = compose.onNodeWithText(sessions[0].name)
            first.assertIsDisplayed()
            assertTrue("$label must show unread badges", compose.onAllNodesWithContentDescription("3 unread messages", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            assertTrue("$label must keep agent icons", compose.onAllNodesWithText("✳", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            assertTrue("$label must show activity times", compose.onAllNodesWithTag("Session activity", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            val title = compose.onNodeWithText(sessions[0].name, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val next = compose.onNodeWithText(sessions[1].name, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val density = compose.activity.resources.displayMetrics.density
            val pitch = (next.top - title.top) / density
            if (pitch > 56f || pitch < 48f) problems += "$label row pitch=$pitch"
            val previews = compose.onAllNodesWithTag("Session preview", useUnmergedTree = true).fetchSemanticsNodes()
            if (previews.isNotEmpty()) problems += "$label still shows status lines"
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val top = title.top.toInt().coerceAtLeast(0)
            val bottom = next.top.toInt().coerceAtMost(bitmap.height)
            val yellow = (top until bottom).sumOf { y -> (0 until bitmap.width).count { x ->
                (pixels[y * bitmap.width + x] and 0xFFFFFF) == (FavoriteMarker.DOT_ARGB.toInt() and 0xFFFFFF)
            } }
            if (yellow > 0) problems += "$label yellow marker pixels=$yellow"
            File(output, "$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            report += "$label pitch=$pitch yellow=$yellow previews=${previews.size}"
            first.performClick()
            compose.runOnIdle { assertEquals(sessions[0].name, opened); opened = null }
        }
        File(output, "rows.txt").writeText((report + problems).joinToString("\n"))
        assertTrue("COMPACT_ROWS: ${problems.joinToString("; ")}", problems.isEmpty())
    }
}
