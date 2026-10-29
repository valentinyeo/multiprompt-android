package dev.multiprompt.companion.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.multiprompt.companion.AppUiState
import dev.multiprompt.companion.MainActivity
import dev.multiprompt.companion.account.AccountLayout
import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RobotFacesTest {
    @get:Rule(order = 0) val notifications = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val host = HostProfile("host", "VPS", "example.com", username = "user", keySecretId = "key")
    private val names = listOf("Cedar agent", "Orbit agent", "Lagoon agent", "Orbit helper", "Lagoon helper", "Cedar helper", "Cedar console", "Lagoon console")
    private val sessions = names.mapIndexed { index, name ->
        TmuxSession("host", "bot-$index", 1, 0, System.currentTimeMillis() / 1000 - index * 120,
            accountName = name, preview = "${name}: latest reply\nshift+tab to cycle")
    }
    private val workspaceNames = listOf("Cedar", "Orbit", "Lagoon", "Orbit", "Lagoon", "Cedar", "Cedar", "Lagoon")
    private var layout by mutableStateOf(AccountLayout.parse(JSONObject("""{"schema":1,"revision":1,"workspaces":[{"name":"Cedar"},{"name":"Orbit"},{"name":"Lagoon"}],"tabs":[${sessions.mapIndexed { index, session ->
        """{"kind":"tmux","name":"${session.displayName}","tmux_session":"${session.name}","tmux_host":0,"workspace":"${workspaceNames[index]}","favorite":${index < 2}}"""
    }.joinToString(",")}]}""")))
    private var opened: TmuxSession? = null
    private var markedUnread: TmuxSession? = null

    @Test fun robotsMatchAcrossInboxPinsAndChatAndActionsStillWork() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                SessionsScreen(
                    state = AppUiState(hosts = listOf(host), sessions = sessions, signedIn = true,
                        accountLayout = layout, accountHosts = mapOf(0 to host), newestSessionsAtBottom = false,
                        unreadSessionKeys = setOf(SessionReadStore.key(host.id, sessions[0].name)),
                        unreadCounts = mapOf(SessionReadStore.key(host.id, sessions[0].name) to 1)),
                    signedIn = true, onSignIn = {}, onSignOut = {}, onOpen = { opened = it }, onOpenTerminal = {},
                    onArchive = {}, onArchiveUntil = { _, _ -> }, onRestore = {}, onDissolve = {}, onEnd = {},
                    onRestoreDissolved = {}, onForgetDissolved = {}, onMarkUnread = { markedUnread = it }, onToggleArchived = {},
                    onSelectSessionBucket = {}, onSelectWorkspace = {}, onSwitchWorkspace = {},
                    onMoveWorkspaceSplit = { _, _ -> }, onResetWorkspaceSplitOrder = {}, onCreateWorkspace = { null },
                    onMoveSession = { _, _ -> }, onRenameSession = { _, _ -> null }, onSetFavorite = { _, _ -> },
                    onRefresh = {}, onSelectSection = {}, onForgetSession = {}, onStartNewSession = {},
                    onSetNewestSessionsAtBottom = {}, onSetAllSplitOnRight = {}, appTheme = AppTheme.DARK,
                    onSetAppTheme = {}, onClearSessionActionError = {}, readerDefaultFontScale = 1f,
                    onSetWorkspaceColor = { name, color -> layout = layout.setWorkspaceColor(name, color) },
                    onSetReaderDefaultFontScale = {}, crashReport = null, onClearCrashReport = {}, onAddHost = {}, material3 = false,
                )
            }
        }
        compose.waitForIdle()
        screenshot("robot-inbox")
        assertWorkspaceHue("bot-0", 0)
        assertWorkspaceHue("bot-1", 1)
        assertWorkspaceHue("bot-2", 2)
        assertWorkspaceHue("bot-3", 1)
        assertWorkspaceHue("bot-4", 2)
        assertWorkspaceHue("bot-5", 0)
        val pin = compose.onNodeWithContentDescription("Robot bot-0", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithContentDescription("Robot bot-2", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertEquals(56f, pin.width / density, .5f)
        assertEquals(48f, row.width / density, .5f)
        assertTrue("Pinned bots must precede inbox rows", pin.bottom < row.top)
        compose.onNodeWithContentDescription("1 unread messages", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Lagoon agent: latest reply", useUnmergedTree = true).assertIsDisplayed()
        val avatar = compose.onNodeWithContentDescription("Robot bot-2", useUnmergedTree = true).captureToImage().asAndroidBitmap()
        val pixels = IntArray(avatar.width * avatar.height)
        avatar.getPixels(pixels, 0, avatar.width, 0, 0, avatar.width, avatar.height)
        assertTrue("Robot must contain real bright eyes, not an empty circle", pixels.count { (it and 0xFFFFFF) == 0xF2F2F2 } > 5)
        avatar.recycle()
        screenshot("robot-inbox")
        compose.onNodeWithText(names[0]).performClick()
        compose.runOnIdle { assertEquals(sessions[0].copy(workspaceName = workspaceNames[0], workspaceColor = layout.workspaceColor(workspaceNames[0])), opened) }
        compose.onNodeWithText(names[2]).performTouchInput { longClick() }
        compose.onNodeWithText("Mark unread").assertIsDisplayed()
        compose.onNodeWithText("Mark unread").performClick()
        compose.runOnIdle { assertEquals(sessions[2].copy(workspaceName = workspaceNames[2], workspaceColor = layout.workspaceColor(workspaceNames[2])), markedUnread) }
        compose.onNodeWithText(names[2]).performClick()
        compose.runOnIdle { assertEquals(sessions[2].copy(workspaceName = workspaceNames[2], workspaceColor = layout.workspaceColor(workspaceNames[2])), opened) }
        compose.onNodeWithContentDescription("Session list actions").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithContentDescription("Orbit color 5").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Orbit color 5").assertIsSelected()
        compose.runOnIdle { assertEquals(4, layout.workspaceColor("Orbit")) }
        screenshot("robot-settings-picker")
        compose.onNodeWithContentDescription("Back").performClick()
        assertWorkspaceHue("bot-1", 4)
        assertWorkspaceHue("bot-3", 4)
        screenshot("robot-inbox-override")
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MultipromptTheme(sunlight = false, material3 = false, readerStyle = true) {
                    Surface {
                        ReaderTopBar(sessions[2].copy(workspaceName = "Lagoon", workspaceColor = layout.workspaceColor("Lagoon")), null, onModelCommand = {}, onOpenTerminal = {},
                            onReleaseNotes = {}, onMenuClick = {}, menuContent = {})
                    }
                }
            }
        }
        compose.waitForIdle()
        val titleRobot = compose.onNodeWithContentDescription("Robot bot-2", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(28f, titleRobot.width / density, .5f)
        compose.onNodeWithText(names[2]).assertIsDisplayed()
        compose.onNodeWithContentDescription("Session actions").assertIsDisplayed()
        assertWorkspaceHue("bot-2", 2)
        screenshot("robot-chat-title")
    }

    private fun assertWorkspaceHue(session: String, color: Int) {
        val bitmap = compose.onNodeWithContentDescription("Robot $session", useUnmergedTree = true).captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val tinted = pixels.count {
            val r = (it shr 16) and 255
            val g = (it shr 8) and 255
            val b = it and 255
            val expected = botColors[color]
            val scale = r / (expected.red * 255.0)
            scale in .60..1.03 && kotlin.math.abs(g - expected.green * 255.0 * scale) < 12 &&
                kotlin.math.abs(b - expected.blue * 255.0 * scale) < 12
        }
        assertTrue("workspace hue $color missing for $session", tinted > pixels.size * .08)
        bitmap.recycle()
    }

    private fun screenshot(name: String) {
        val directory = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "robot-faces").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
