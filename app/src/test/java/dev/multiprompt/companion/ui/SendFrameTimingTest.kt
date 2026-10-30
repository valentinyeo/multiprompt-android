package dev.multiprompt.companion.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalView
import org.robolectric.shadows.ShadowLog
import org.robolectric.annotation.GraphicsMode
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
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
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SendFrameTimingTest {
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

    private var drawing by mutableStateOf(false)
    private lateinit var view: View

    private fun render() {
        compose.setContent {
            MaterialTheme {
                view = LocalView.current
                Box(Modifier.drawWithContent { if (drawing) drawContent() }) {
                val visibleConnection = if (switched) other else connection
                val visibleSession = if (switched) f.session.copy(name = "two") else f.session
                ReaderScreen(
                    connection = visibleConnection, dictation = f.app.deepgramDictation,
                    screencast = f.app.screencastUploader,
                    signedIn = true, onSignIn = {}, onSignOut = {}, session = visibleSession,
                    unread = false, archived = false, initialFontScale = 1f,
                    onBack = {}, onMarkRead = {}, onArchiveToggle = {}, onRemind = {},
                    onOpenTerminal = {}, onFontScaleChanged = {},
                    onRename = { null }, onDissolve = {}, onEnd = {}, onSessionInteraction = {},
                    onSessionInput = { f.model.stampSessionInput(visibleSession) },
                    technicalMode = false, onTechnicalModeChanged = {}, sunlightMode = false,
                    onSetAppTheme = {}, onResetFontScale = { 1f }, sessionActionError = null,
                    onClearSessionActionError = {}, onForget = {}, onShowEtaAccuracy = {}, onReleaseNotes = {},
                )
                }
            }
        }
    }

    @Test fun clearedFrameReceiptRequiresAnActualComposerDraw() {
        render()
        compose.onNodeWithText("Message ${f.session.displayName}").performTextInput("draw me")
        ShadowLog.clear()
        compose.onNodeWithContentDescription("Send prompt").performClick()
        compose.waitForIdle()
        compose.runOnUiThread {
            assertEquals(listOf("draw me"), connection.state.value.sentPrompts)
            assertFalse("A frame-clock callback cannot prove hidden content was drawn", marked())
            drawing = true
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            val root = view.rootView
            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(image))
            assertTrue("Drawing the empty composer must record the cleared frame", marked())
            root.draw(Canvas(image))
            assertEquals(1, ShadowLog.getLogsForTag("SendTiming").count { it.msg.startsWith("box_cleared_frame ") })
            image.recycle()
        }
    }

    private fun marked() = ShadowLog.getLogsForTag("SendTiming").any { it.msg.startsWith("box_cleared_frame ") }
}
