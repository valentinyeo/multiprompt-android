package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
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
    private fun input() = f.model.state.value.accountLayout!!.tabs.first().input

    @Before fun setup() {
        f = SessionActivityFixture()
        connection = SessionReaderConnection(f.app.sshRepository, f.host, f.session.name, historyScript = byteArrayOf(),
            historyCache = ChatHistoryCache(f.app.cacheDir.resolve("session-input-history")))
    }
    @After fun cleanup() { connection.close(); f.close() }

    private fun render() {
        compose.setContent {
            MaterialTheme {
                ReaderScreen(
                    connection = connection, dictation = f.app.deepgramDictation,
                    screencast = f.app.screencastUploader, skills = f.app.skillStore,
                    signedIn = true, onSignIn = {}, onSignOut = {}, session = f.session,
                    unread = false, archived = false, initialFontScale = 1f,
                    onBack = {}, onMarkRead = {}, onArchiveToggle = {}, onRemind = {},
                    onOpenTerminal = {}, onSwitchSession = {}, onFontScaleChanged = {},
                    onRename = { null }, onDissolve = {}, onEnd = {}, onSessionInteraction = {},
                    onSessionInput = { f.model.stampSessionInput(f.session) },
                    technicalMode = false, onTechnicalModeChanged = {}, sunlightMode = false,
                    onSetAppTheme = {}, onResetFontScale = { 1f }, sessionActionError = null,
                    onClearSessionActionError = {}, onForget = {}, onShowEtaAccuracy = {},
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
        compose.runOnIdle { dictation.value = DictationState(DictationStatus.LISTENING, "spoken", true) }
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
