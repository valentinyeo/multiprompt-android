package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.performSemanticsAction
import org.robolectric.shadows.ShadowDialog
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.reader.ChatHistoryConnection
import dev.multiprompt.companion.reader.ChatHistoryProtocol
import dev.multiprompt.companion.reader.ChatHistoryState
import dev.multiprompt.companion.reader.ChatHistoryCache
import org.robolectric.util.ReflectionHelpers
import dev.multiprompt.companion.reader.ReaderState
import dev.multiprompt.companion.reader.ReaderStatus
import dev.multiprompt.companion.reader.SessionReaderConnection
import kotlinx.coroutines.flow.MutableStateFlow
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderActionsMenuTest {
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(scheduler))
    private lateinit var f: SessionActivityFixture
    private lateinit var connection: SessionReaderConnection
    private var archives = 0
    private var waits = 0
    private val reminders = mutableListOf<Long?>()
    private val archived = mutableStateOf(false)

    @Before fun setup() {
        f = SessionActivityFixture()
        val cache = ChatHistoryCache(f.app.cacheDir.resolve("actions-history"))
        connection = SessionReaderConnection(f.app.sshRepository, f.host, f.session.name,
            agent = AgentKind.CLAUDE, historyScript = byteArrayOf(), historyCache = cache)
        val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, f.session.name, f.session.workingDirectory)
        val chat = ChatHistoryConnection(f.app.sshRepository, f.host, identity, byteArrayOf(), cache)
        f.field<MutableStateFlow<ChatHistoryState>>(chat, "_state").value = ChatHistoryState(loaded = true)
        ReflectionHelpers.setField(connection, "historyIdentity", identity)
        ReflectionHelpers.setField(connection, "historyConnection", chat)
    }

    @After fun cleanup() { connection.close(); f.close() }

    private fun render(material3: Boolean = false, sending: Boolean = false) {
        f.field<MutableStateFlow<ReaderState>>(connection, "_state").value = ReaderState(status = ReaderStatus.Live, sending = sending)
        compose.setContent {
            ReaderScreen(
                connection = connection, dictation = f.app.deepgramDictation,
                screencast = f.app.screencastUploader, skills = f.app.skillStore,
                signedIn = true, onSignIn = {}, onSignOut = {}, session = f.session.copy(paneCommand = "claude"),
                unread = false, archived = archived.value, initialFontScale = 1f,
                onBack = {}, onMarkRead = {}, onArchiveToggle = { waits++; archived.value = !archived.value },
                onRemind = { reminders.add(it) }, onOpenTerminal = {}, onSwitchSession = {},
                onFontScaleChanged = {}, onRename = { null }, onDissolve = { archives++ }, onEnd = {},
                onSessionInteraction = {}, onSessionInput = {}, technicalMode = false,
                onTechnicalModeChanged = {}, sunlightMode = false, onSetAppTheme = {},
                onResetFontScale = { 1f }, sessionActionError = null, onClearSessionActionError = {},
                onForget = {}, onShowEtaAccuracy = {}, onReleaseNotes = {}, material3 = material3,
            )
        }
    }

    private fun openMenu() { compose.onNodeWithContentDescription("Session actions").performClick() }

    @Test fun archiveFromOverflowKeepsTheExistingDirectArchiveBehaviour() {
        render()
        openMenu()
        compose.onNodeWithText("Archive session").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, archives); assertEquals(0, waits); assertTrue(reminders.isEmpty()) }
        compose.onNodeWithText("Archive session").assertDoesNotExist()
    }

    @Test fun remindFromOverflowOpensTheExistingPickerAndDispatchesItsDueTime() {
        render(material3 = true)
        openMenu()
        val remind = compose.onNodeWithText("Remind me").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        remind.performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnUiThread { Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        scheduler.runCurrent()
        compose.runOnUiThread {
            val dialog = checkNotNull(ShadowDialog.getLatestDialog())
            val viewport = dialog.context.resources.displayMetrics
            checkNotNull(dialog.window).setLayout(viewport.widthPixels, viewport.heightPixels)
        }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Tomorrow · 9:00 AM").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, reminders.size)
            assertEquals(dev.multiprompt.companion.data.ReminderTimeParser.tomorrowMorning(java.time.ZonedDateTime.now()).toEpochSecond(), reminders.single())
            assertEquals(0, archives)
            assertEquals(0, waits)
        }
        compose.onNodeWithText("Tomorrow · 9:00 AM").assertDoesNotExist()
    }

    @Test fun waitingFromOverflowTogglesBackToOpenEvenWhileSending() {
        render(sending = true)
        openMenu()
        compose.onNodeWithText("Waiting").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, waits); assertTrue(archived.value) }
        compose.onNodeWithText("Return to open").assertDoesNotExist()
        openMenu()
        compose.onNodeWithText("Return to open").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, waits); assertTrue(!archived.value); assertEquals(0, archives) }
    }

    @Test fun composerHasInsideMicrophoneOutsidePlusAndNoWaveform() {
        render()
        val input = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val mic = compose.onNodeWithContentDescription("Start dictation").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val plus = compose.onNodeWithContentDescription("Attach").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(mic.left >= input.left && mic.right <= input.right && mic.top >= input.top && mic.bottom <= input.bottom)
        assertTrue(plus.right < input.left)
        compose.onNodeWithTag("reader-waveform").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithText(f.session.displayName).assertIsDisplayed()
    }

    @Test fun sessionActionsAreNotDuplicatedAboveTheComposer() {
        render(material3 = true)
        listOf("Archive", "Remind", "Wait", "Open", "Archive session", "Remind me", "Waiting").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        openMenu()
        listOf("Archive session", "Remind me", "Waiting").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test fun overflowMenuIsClusteredIntoShortGroupsWithSubPages() {
        render(material3 = true)
        openMenu()
        listOf(
            "Enter / approve", "Interrupt session", "Open live terminal", "Rename", "Remind me", "Waiting",
            "Archive session", "Model and effort ›", "Display ›", "More ›", "End session", "Remove from list",
        ).forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        listOf(
            "Set effort: low", "Set effort: max", "Use Claude Opus", "Open model picker (/model)", "Sunlight mode on",
            "Show terminal details", "Use default font size", "Release notes", "Estimate accuracy", "Dictation API key",
            "Screencast2 upload key", "Already read", "Current: Unknown",
        ).forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test fun modelAndEffortSubPageHoldsModelChoicesAndBackReturnsToTheMainMenu() {
        render(material3 = true)
        openMenu()
        compose.onNodeWithText("Model and effort ›").performClick()
        listOf("Open model picker (/model)", "Use Claude Opus", "Use Claude Sonnet", "Use Claude Fable", "Set effort: low", "Set effort: max")
            .forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Enter / approve").assertDoesNotExist()
        compose.onNodeWithText("‹ Back").performClick()
        compose.onNodeWithText("Enter / approve").assertIsDisplayed()
        compose.onNodeWithText("Set effort: low").assertDoesNotExist()
    }

    @Test fun displayAndMoreSubPagesHoldTheRemainingOptions() {
        render(material3 = true)
        openMenu()
        compose.onNodeWithText("Display ›").performClick()
        listOf("Show terminal details", "Sunlight mode on", "Use default font size").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("‹ Back").performClick()
        compose.onNodeWithText("More ›").performClick()
        listOf("Release notes", "Estimate accuracy", "Dictation API key", "Screencast2 upload key")
            .forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Copy raw transcript", substring = true).assertIsDisplayed()
    }

    @Test fun reopeningTheMenuAlwaysStartsOnTheMainPage() {
        render(material3 = true)
        openMenu()
        compose.onNodeWithText("Display ›").performClick()
        compose.onNodeWithText("Sunlight mode on").performClick()
        openMenu()
        compose.onNodeWithText("Enter / approve").assertIsDisplayed()
        compose.onNodeWithText("Sunlight mode on").assertDoesNotExist()
    }
}
