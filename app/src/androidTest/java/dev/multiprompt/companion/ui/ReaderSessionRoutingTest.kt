package dev.multiprompt.companion.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.platform.app.InstrumentationRegistry
import dev.multiprompt.companion.MultipromptApplication
import dev.multiprompt.companion.dictation.DictationState
import dev.multiprompt.companion.dictation.DictationStatus
import dev.multiprompt.companion.model.TmuxSession
import dev.multiprompt.companion.reader.ReaderStatus
import dev.multiprompt.companion.reader.SessionReaderConnection
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReaderSessionRoutingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fastSwitchesKeepTypedAndFinalVoiceMessagesOnTheirVisibleSession() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MultipromptApplication
        val host = app.hostStore.load().single { it.label == "Verify" }
        val a = TmuxSession(host.id, "verify-session", 1, 0, 0)
        val b = a.copy(name = "verify-other")
        fun reader(session: TmuxSession) = SessionReaderConnection(app.sshRepository, host, session.name,
            historyScript = app.chatHistoryScript, historyCache = app.chatHistoryCache).apply { start() }
        val first = reader(a)
        val second = reader(b)
        var switched by mutableStateOf(false)
        val field = app.deepgramDictation.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val voice = field.get(app.deepgramDictation) as MutableStateFlow<DictationState>
        try {
            compose.setContent {
                val connection = if (switched) second else first
                ReaderScreen(connection = connection, dictation = app.deepgramDictation,
                    screencast = app.screencastUploader, skills = app.skillStore,
                    signedIn = true, onSignIn = {}, onSignOut = {}, session = if (switched) b else a,
                    unread = false, archived = false, initialFontScale = 1f,
                    onBack = {}, onMarkRead = {}, onArchiveToggle = {}, onRemind = {},
                    onOpenTerminal = {}, onSwitchSession = { switched = !switched }, onFontScaleChanged = {},
                    onRename = { null }, onDissolve = {}, onEnd = {}, onSessionInteraction = {}, onSessionInput = {},
                    technicalMode = false, onTechnicalModeChanged = {}, sunlightMode = false,
                    onSetAppTheme = {}, onResetFontScale = { 1f }, sessionActionError = null,
                    onClearSessionActionError = {}, onForget = {}, onShowEtaAccuracy = {}, onReleaseNotes = {})
            }
            compose.waitUntil(30_000) { first.state.value.status == ReaderStatus.Live && second.state.value.status == ReaderStatus.Live }
            listOf(false, true).forEach { target ->
                repeat(4) { compose.runOnIdle { switched = !switched } }
                if (target) compose.onRoot().performTouchInput { swipeLeft() }
                else compose.runOnIdle { switched = false }
                val connection = if (target) second else first
                val suffix = if (target) "B" else "A"
                compose.onNodeWithText("Messaging ${if (target) b.name else a.name}").assertIsDisplayed()
                compose.onNodeWithText("Message this session").performTextInput("TYPED_$suffix")
                compose.onNodeWithContentDescription("Send prompt").performClick()
                compose.waitUntil(30_000) { connection.state.value.completedActions == 1L }
                compose.runOnIdle { voice.value = DictationState(DictationStatus.LISTENING, "VOICE_$suffix", true, sessionKey = "${host.id}::${if (target) b.name else a.name}") }
                compose.onNodeWithContentDescription("Send prompt").performClick()
                compose.waitUntil(30_000) { connection.state.value.completedActions == 2L }
            }
            compose.runOnIdle { switched = false }
            compose.runOnIdle {
                app.deepgramDictation.javaClass.getDeclaredField("stopping").apply { isAccessible = true }
                    .setBoolean(app.deepgramDictation, true)
                voice.value = DictationState(DictationStatus.FINISHING, "LATE_ONLY_A", true, sessionKey = "${host.id}::${a.name}")
            }
            compose.onNodeWithContentDescription("Send prompt").performClick()
            compose.runOnIdle { switched = true }
            compose.runOnIdle { assertEquals(DictationStatus.IDLE, voice.value.status) }
            compose.onNodeWithText("Message this session").performTextInput("AFTER_SWITCH_B")
            compose.onNodeWithContentDescription("Send prompt").performClick()
            compose.waitUntil(30_000) { second.state.value.completedActions == 3L }
            compose.onNodeWithText("Messaging verify-other").assertIsDisplayed()
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            File(app.getExternalFilesDir(null), "routing-b.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            assertEquals(listOf("TYPED_A", "VOICE_A"), first.state.value.sentPrompts)
            assertEquals(listOf("TYPED_B", "VOICE_B", "AFTER_SWITCH_B"), second.state.value.sentPrompts)
        } finally {
            first.close()
            second.close()
            app.deepgramDictation.discard()
        }
    }
}
