package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import dev.multiprompt.companion.ssh.TmuxText
import androidx.compose.ui.test.junit4.createComposeRule
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
class ReaderProgressBarTest {
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(scheduler))
    private lateinit var f: SessionActivityFixture
    private lateinit var connection: SessionReaderConnection
    @Before fun setup() {
        f = SessionActivityFixture()
        val cache = ChatHistoryCache(f.app.cacheDir.resolve("progress-bar-history"))
        connection = SessionReaderConnection(f.app.sshRepository, f.host, f.session.name,
            agent = AgentKind.CLAUDE, historyScript = byteArrayOf(), historyCache = cache)
        val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, f.session.name, f.session.workingDirectory)
        val chat = ChatHistoryConnection(f.app.sshRepository, f.host, identity, byteArrayOf(), cache)
        f.field<MutableStateFlow<ChatHistoryState>>(chat, "_state").value = ChatHistoryState(loaded = true)
        ReflectionHelpers.setField(connection, "historyIdentity", identity)
        ReflectionHelpers.setField(connection, "historyConnection", chat)
    }

    @After fun cleanup() { connection.close(); f.close() }

    private fun render() {
        f.field<MutableStateFlow<ReaderState>>(connection, "_state").value = ReaderState(status = ReaderStatus.Live, sending = false)
        compose.setContent {
            ReaderScreen(
                connection = connection, dictation = f.app.deepgramDictation,
                screencast = f.app.screencastUploader,
                signedIn = true, onSignIn = {}, onSignOut = {}, session = f.session.copy(paneCommand = "claude"),
                unread = false, archived = false, initialFontScale = 1f,
                onBack = {}, onMarkRead = {}, onArchiveToggle = {},
                onRemind = {}, onOpenTerminal = {}, onSwitchSession = {},
                onFontScaleChanged = {}, onRename = { null }, onDissolve = {}, onEnd = {},
                onSessionInteraction = {}, onSessionInput = {}, technicalMode = false,
                onTechnicalModeChanged = {}, sunlightMode = false, onSetAppTheme = {},
                onResetFontScale = { 1f }, sessionActionError = null, onClearSessionActionError = {},
                onForget = {}, onShowEtaAccuracy = {}, onReleaseNotes = {}, material3 = false,
            )
        }
    }

    @Test fun etaDoesNotAddABarAboveComposerWhileIdleOrSending() {
        render()
        val viewport = compose.onNodeWithTag("chat-history")
        val idleBounds = viewport.getUnclippedBoundsInRoot()
        val reader = f.field<MutableStateFlow<ReaderState>>(connection, "_state")
        for (sending in listOf(false, true)) {
            compose.runOnIdle {
                reader.value = reader.value.copy(sending = sending,
                    eta = TmuxText.SessionEta(System.currentTimeMillis() + 120_000, 240_000, ""))
            }
            assertEquals("ETA must not shrink chat above the composer (sending=$sending)",
                idleBounds, viewport.getUnclippedBoundsInRoot())
            compose.runOnIdle { reader.value = reader.value.copy(eta = null) }
        }
    }
}
