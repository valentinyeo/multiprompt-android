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
            val metrics = dialog.context.resources.displayMetrics
            println("Reminder window: ${dialog.window?.decorView?.width}x${dialog.window?.decorView?.height}")
            dialog.window?.setLayout(metrics.widthPixels, metrics.heightPixels)
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

    @Test fun composerHasInsideMicrophoneOutsidePlusAndDecorativeWaveform() {
        render()
        val input = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val mic = compose.onNodeWithContentDescription("Start dictation").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val plus = compose.onNodeWithContentDescription("Attach").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val waveform = compose.onNodeWithTag("reader-waveform").assertIsDisplayed().fetchSemanticsNode()
        assertTrue(mic.left >= input.left && mic.right <= input.right && mic.top >= input.top && mic.bottom <= input.bottom)
        assertTrue(plus.right < input.left)
        assertTrue(waveform.boundsInRoot.left > input.right)
        assertTrue(SemanticsActions.OnClick !in waveform.config)
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithText(f.session.displayName).assertDoesNotExist()
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
}
