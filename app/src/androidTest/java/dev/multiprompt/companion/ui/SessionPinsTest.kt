package dev.multiprompt.companion.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.multiprompt.companion.AppUiState
import dev.multiprompt.companion.MainActivity
import dev.multiprompt.companion.account.AccountLayout
import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import dev.multiprompt.companion.model.Workspace
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SessionPinsTest {
    @get:Rule(order = 0)
    val notifications = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private var opened: TmuxSession? = null
    private val host = HostProfile("host", "VPS", "example.com", username = "user", keySecretId = "key")
    private val workspace = Workspace("work", "Android", "host", "/home/user/android")
    private val sessions = List(40) { index ->
        TmuxSession(
            "host",
            if (index < 12) "pin-%02d".format(index) else "session-%02d".format(index),
            1,
            0,
            1_799_999_880 - index * 60L,
            preview = "Session preview $index",
        )
    }
    private val layout = AccountLayout.parse(
        JSONObject(
            """{"schema":1,"revision":1,"workspaces":[{"name":"Android"}],"tabs":[${sessions.mapIndexed { index, session ->
                """{"kind":"tmux","name":"${session.name}","tmux_session":"${session.name}","tmux_host":0,"workspace":"Android","favorite":${index < 12},"input":${1_799_999_940_000L - index * 60_000L}}"""
            }.joinToString(",")}]}"""
        )
    )
    private val state = mutableStateOf(
        AppUiState(
            hosts = listOf(host),
            sessions = sessions,
            signedIn = true,
            workspaces = listOf(workspace),
            workspaceSplitIds = listOf(null, workspace.id),
            accountLayout = layout,
            accountHosts = mapOf(0 to host),
            sessionWorkspaceIds = sessions.associate {
                SessionReadStore.key(it.hostId, it.name) to workspace.id
            },
            unreadSessionKeys = sessions.map { SessionReadStore.key(it.hostId, it.name) }.toSet(),
            unreadCounts = sessions.associate { SessionReadStore.key(it.hostId, it.name) to 3 },
            newestSessionsAtBottom = false,
        )
    )

    private val vertical = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
    private val strip = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and
        hasAnyDescendant(hasText("pin-", substring = true))

    private fun render(material3: Boolean, reversed: Boolean) {
        compose.runOnIdle {
            opened = null
            state.value = state.value.copy(newestSessionsAtBottom = reversed)
        }
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                SessionsScreen(
                    state = state.value, signedIn = true, onSignIn = {}, onSignOut = {},
                    onOpen = { opened = it }, onOpenTerminal = {}, onArchive = {}, onArchiveUntil = { _, _ -> },
                    onRestore = {}, onDissolve = {}, onEnd = {}, onRestoreDissolved = {}, onForgetDissolved = {},
                    onMarkUnread = {}, onToggleArchived = {}, onSelectSessionBucket = {}, onSelectWorkspace = {},
                    onSwitchWorkspace = {}, onMoveWorkspaceSplit = { _, _ -> }, onResetWorkspaceSplitOrder = {},
                    onCreateWorkspace = { null }, onMoveSession = { _, _ -> }, onRenameSession = { _, _ -> null },
                    onSetFavorite = { tab, value ->
                        state.value = state.value.copy(
                            accountLayout = state.value.accountLayout!!.setFavorite(tab.identity, value)
                        )
                    }, onRefresh = {}, onSelectSection = {}, onForgetSession = {},
                    onStartNewSession = {}, onSetNewestSessionsAtBottom = {}, onSetAllSplitOnRight = {},
                    appTheme = AppTheme.DARK, onSetAppTheme = {}, onClearSessionActionError = {},
                    readerDefaultFontScale = 1f, onSetReaderDefaultFontScale = {}, crashReport = null,
                    onClearCrashReport = {}, onMapAccountHost = { _, _ -> },
                    material3 = material3, nowMillis = { 1_800_000_000_000 },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun displayed(name: String): Boolean =
        compose.onNodeWithText(name, useUnmergedTree = true).isDisplayed()

    private fun assertUnreadDisplayed() {
        val unread = compose.onAllNodes(
            hasContentDescription("3 unread messages"),
            useUnmergedTree = true,
        )
        assertTrue("An unread count must be displayed", unread.fetchSemanticsNodes().indices.any { unread[it].isDisplayed() })
    }

    private fun verticalNode() = compose.onNode(vertical, useUnmergedTree = true)

    private fun stripNode() = compose.onNode(strip, useUnmergedTree = true)

    private fun reachPhysicalTop() {
        repeat(50) {
            val before = verticalNode().fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value()
            verticalNode().performTouchInput { swipeDown(startY = height * 0.35f, endY = height * 0.85f) }
            compose.waitForIdle()
            val after = verticalNode().fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value()
            if (before == after) {
                assertTrue("pin-00 must be displayed at physical top", displayed("pin-00"))
                return
            }
        }
        throw AssertionError("Could not reach physical top with bounded swipeDown gestures")
    }

    private fun capture(case: String, stage: String) {
        compose.waitForIdle()
        val output = File(
            checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
            "session-pins",
        ).apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(output, "$case-$stage.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun runCase(case: String, material3: Boolean, reversed: Boolean) {
        render(material3, reversed)
        assertEquals(1, compose.onAllNodes(vertical, useUnmergedTree = true).fetchSemanticsNodes().size)
        reachPhysicalTop()
        assertEquals(1, compose.onAllNodes(strip, useUnmergedTree = true).fetchSemanticsNodes().size)
        capture(case, "top")

        val inbox = compose.onNodeWithText("Inbox", useUnmergedTree = true)
        val search = compose.onNodeWithContentDescription("Search sessions", useUnmergedTree = true)
        val newSession = compose.onNodeWithContentDescription("New session", useUnmergedTree = true)
        val actions = compose.onNodeWithContentDescription("Session list actions", useUnmergedTree = true)
        val fixed = listOf(inbox, search, newSession, actions) +
            listOf("All", "Favorites", "Frequent", "Android").map {
                compose.onNodeWithText(it, useUnmergedTree = true)
            }
        val bounds = fixed.map {
            assertTrue(it.isDisplayed())
            it.fetchSemanticsNode().boundsInRoot
        }
        assertUnreadDisplayed()

        repeat(12) {
            if (!displayed("pin-11")) stripNode().performTouchInput { swipeLeft() }
        }
        assertTrue("pin-11 must be reachable horizontally", displayed("pin-11"))
        compose.onNodeWithText("pin-11", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(sessions[11], opened) }

        repeat(12) {
            if (!displayed("pin-00")) stripNode().performTouchInput { swipeRight() }
        }
        assertTrue("pin-00 must be reachable again", displayed("pin-00"))
        compose.waitForIdle()
        val topImage = compose.onRoot().captureToImage().asAndroidBitmap()

        repeat(2) { verticalNode().performTouchInput { swipeUp(startY = height * 0.85f, endY = height * 0.35f) } }
        capture(case, "scrolled")
        val scrolledImage = compose.onRoot().captureToImage().asAndroidBitmap()
        fixed.forEachIndexed { index, node ->
            assertTrue("Fixed control $index must remain displayed", node.isDisplayed())
            assertEquals("Fixed control $index moved", bounds[index], node.fetchSemanticsNode().boundsInRoot)
        }
        assertUnreadDisplayed()
        assertFalse("PINNED_STRIP_MUST_SCROLL: pin-00 is still displayed", displayed("pin-00"))
        assertFalse("PINNED_STRIP_MUST_SCROLL: pin-11 is still displayed", displayed("pin-11"))

        val visibleRows = (12..39).mapNotNull { index ->
            val node = compose.onNodeWithText("session-%02d".format(index), useUnmergedTree = true)
            if (node.isDisplayed()) {
                val rowBounds = node.fetchSemanticsNode().boundsInRoot
                if (rowBounds.height > 0f) index to rowBounds.top else null
            } else {
                null
            }
        }.sortedBy { it.second }
        assertTrue("At least two regular rows must be visible", visibleRows.size >= 2)
        assertTrue(
            "Visible regular rows must follow physical top-to-bottom order",
            visibleRows.zipWithNext().all { (first, second) ->
                first.second < second.second &&
                    if (reversed) first.first > second.first else first.first < second.first
            },
        )

        assertEquals(topImage.width, scrolledImage.width)
        assertEquals(topImage.height, scrolledImage.height)
        val top = floor(bounds.take(4).minOf { it.top }).toInt().coerceIn(0, topImage.height)
        val bottom = ceil(bounds.take(4).maxOf { it.bottom }).toInt().coerceIn(0, topImage.height)
        assertTrue("Header pixel range must be nonempty", bottom > top && topImage.width > 0)
        var changedPixels = 0
        for (y in top until bottom) {
            for (x in 0 until topImage.width) {
                if (topImage.getPixel(x, y) != scrolledImage.getPixel(x, y)) changedPixels++
            }
        }
        assertEquals("Top controls must conceal scrolling rows", 0, changedPixels)

        reachPhysicalTop()
        capture(case, "restored")
    }

    @Test
    fun readerNormal() = runCase("reader-normal", material3 = false, reversed = false)

    @Test
    fun readerReversed() = runCase("reader-reversed", material3 = false, reversed = true)

    @Test
    fun material3Normal() = runCase("m3-normal", material3 = true, reversed = false)

    @Test
    fun material3Reversed() = runCase("m3-reversed", material3 = true, reversed = true)
}
