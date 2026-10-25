package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.multiprompt.companion.dictation.DictationState
import dev.multiprompt.companion.dictation.DictationStatus
import dev.multiprompt.companion.reader.ChatHistoryCache
import dev.multiprompt.companion.reader.SessionReaderConnection
import dev.multiprompt.companion.terminal.TerminalInputTracker
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SessionInputTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var f: SessionActivityFixture
    private lateinit var connection: SessionReaderConnection
    private lateinit var other: SessionReaderConnection
    private var switched by mutableStateOf(false)
    private fun input() = f.model.state.value.accountLayout!!.tabs.first().input

    @Before fun setup() {
        f = SessionActivityFixture()
        connection = SessionReaderConnection(f.app.sshRepository, f.host, f.session.name, historyScript = byteArrayOf(),
            historyCache = ChatHistoryCache(f.app.cacheDir.resolve("session-input-history")))
        other = SessionReaderConnection(f.app.sshRepository, f.host, "two", historyScript = byteArrayOf(),
            historyCache = ChatHistoryCache(f.app.cacheDir.resolve("session-input-history-two")))
    }
    @After fun cleanup() { connection.close(); other.close(); f.close() }

    private fun render() {
        compose.setContent {
            MaterialTheme {
                val visibleConnection = if (switched) other else connection
                val visibleSession = if (switched) f.session.copy(name = "two") else f.session
                ReaderScreen(
                    connection = visibleConnection, dictation = f.app.deepgramDictation,
                    screencast = f.app.screencastUploader, skills = f.app.skillStore,
                    signedIn = true, onSignIn = {}, onSignOut = {}, session = visibleSession,
                    unread = false, archived = false, initialFontScale = 1f,
                    onBack = {}, onMarkRead = {}, onArchiveToggle = {}, onRemind = {},
                    onOpenTerminal = {}, onSwitchSession = {}, onFontScaleChanged = {},
                    onRename = { null }, onDissolve = {}, onEnd = {}, onSessionInteraction = {},
                    onSessionInput = { f.model.stampSessionInput(visibleSession) },
                    technicalMode = false, onTechnicalModeChanged = {}, sunlightMode = false,
                    onSetAppTheme = {}, onResetFontScale = { 1f }, sessionActionError = null,
                    onClearSessionActionError = {}, onForget = {}, onShowEtaAccuracy = {}, onReleaseNotes = {},
                )
            }
        }
    }

    @Test fun composerSendStampsInputButEditingTheDraftDoesNot() {
        render()
        compose.onNodeWithText("Message this session").performTextInput("hello")
        compose.runOnIdle { assertNull(input()) }
        val before = System.currentTimeMillis()
        compose.onNodeWithContentDescription("Send prompt").performClick()
        compose.runOnIdle {
            assertTrue(input()!! >= before)
            assertEquals(input(), f.model.state.value.accountLayout!!.tabs.first().used)
            assertNull(f.model.state.value.accountLayout!!.tabs.first().visits)
            assertEquals(listOf("hello"), connection.state.value.sentPrompts)
        }
    }

    @Test fun voiceSendStampsInputOnlyAfterFinalTranscriptIsSubmitted() {
        render()
        val dictation = f.field<MutableStateFlow<DictationState>>(f.app.deepgramDictation, "_state")
        compose.runOnIdle {
            f.app.deepgramDictation.javaClass.getDeclaredField("stopping").apply { isAccessible = true }
                .setBoolean(f.app.deepgramDictation, true)
            dictation.value = DictationState(DictationStatus.FINISHING, "spoken", true)
        }
        compose.runOnIdle { assertNull(input()) }
        compose.onNodeWithContentDescription("Send prompt").performClick()
        compose.runOnIdle { assertNull(input()) }
        val before = System.currentTimeMillis()
        compose.runOnIdle { dictation.value = DictationState(DictationStatus.IDLE, "spoken final", true) }
        compose.runOnIdle {
            assertTrue(input()!! >= before)
            assertEquals(listOf("spoken final"), connection.state.value.sentPrompts)
        }
    }

    @Test fun voiceSendWithoutASocketSubmitsWhenStopImmediatelyEndsDictation() {
        render()
        val dictation = f.field<MutableStateFlow<DictationState>>(f.app.deepgramDictation, "_state")
        compose.runOnIdle { dictation.value = DictationState(DictationStatus.LISTENING, "spoken", true) }
        compose.runOnIdle { assertNull(input()) }
        val before = System.currentTimeMillis()
        compose.onNodeWithContentDescription("Send prompt").performClick()
        compose.runOnIdle {
            assertEquals(DictationStatus.IDLE, dictation.value.status)
            assertTrue(input()!! >= before)
            assertEquals(listOf("spoken"), connection.state.value.sentPrompts)
        }
    }

    @Test fun typedAndDictatedSendsFollowTheVisibleSessionAfterRapidSwitches() {
        render()
        val dictation = f.field<MutableStateFlow<DictationState>>(f.app.deepgramDictation, "_state")
        repeat(4) { index ->
            compose.runOnIdle { switched = index % 2 == 0 }
            compose.onNodeWithText("Message this session").performTextInput("typed $index")
            compose.onNodeWithContentDescription("Send prompt").performClick()
            compose.runOnIdle {
                val visible = if (switched) other else connection
                f.field<MutableStateFlow<dev.multiprompt.companion.reader.ReaderState>>(visible, "_state").value =
                    visible.state.value.copy(completedActions = visible.state.value.completedActions + 1)
            }
            compose.runOnIdle {
                dictation.value = DictationState(DictationStatus.LISTENING, "spoken $index", true)
            }
            compose.onNodeWithTag("dictation-session-line").assertTextEquals("Messaging ${if (index % 2 == 0) "two" else "one"}")
            compose.onNodeWithContentDescription("Send prompt").performClick()
            compose.runOnIdle {
                val visible = if (switched) other else connection
                f.field<MutableStateFlow<dev.multiprompt.companion.reader.ReaderState>>(visible, "_state").value =
                    visible.state.value.copy(completedActions = visible.state.value.completedActions + 1)
            }
        }
        compose.runOnIdle {
            assertEquals(listOf("typed 0", "spoken 0", "typed 2", "spoken 2"), other.state.value.sentPrompts)
            assertEquals(listOf("typed 1", "spoken 1", "typed 3", "spoken 3"), connection.state.value.sentPrompts)
        }
    }

    @Test fun switchingDuringFinalizationDiscardsVoiceInsteadOfSendingToTheNewSession() {
        render()
        val dictation = f.field<MutableStateFlow<DictationState>>(f.app.deepgramDictation, "_state")
        compose.runOnIdle {
            f.app.deepgramDictation.javaClass.getDeclaredField("stopping").apply { isAccessible = true }
                .setBoolean(f.app.deepgramDictation, true)
            dictation.value = DictationState(DictationStatus.FINISHING, "only for one", true)
        }
        compose.onNodeWithContentDescription("Send prompt").performClick()
        compose.runOnIdle { switched = true }
        compose.runOnIdle {
            assertEquals(DictationStatus.IDLE, dictation.value.status)
            assertTrue(connection.state.value.sentPrompts.isEmpty())
            assertTrue(other.state.value.sentPrompts.isEmpty())
        }
        compose.onNodeWithText("Message this session").performTextInput("only for two")
        compose.onNodeWithContentDescription("Send prompt").performClick()
        compose.runOnIdle { assertEquals(listOf("only for two"), other.state.value.sentPrompts) }
    }

    @Test fun terminalKeystrokesStampInputNotVisitsAndQueriesDoNot() {
        val tracker = TerminalInputTracker { f.model.stampSessionInput(f.session) }
        tracker.isCtrlActive()
        tracker.isAltActive()
        tracker.isShiftActive()
        assertNull(input())
        val before = System.currentTimeMillis()
        tracker.clearTransients()
        assertTrue(input()!! >= before)
        assertNull(f.model.state.value.accountLayout!!.tabs.first().visits)
        repeat(100) { tracker.clearTransients() }
        assertTrue(input()!! >= before)
    }
}
