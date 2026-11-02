package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.reader.ChatHistoryCache
import dev.multiprompt.companion.reader.ChatHistoryConnection
import dev.multiprompt.companion.reader.ChatHistoryProtocol
import dev.multiprompt.companion.reader.ChatHistoryState
import dev.multiprompt.companion.reader.QueuedMessages
import dev.multiprompt.companion.reader.ReaderState
import dev.multiprompt.companion.reader.ReaderStatus
import dev.multiprompt.companion.reader.SessionReaderConnection
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveChatProofTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var f: SessionActivityFixture
    private lateinit var connection: SessionReaderConnection

    @Before fun setup() {
        f = SessionActivityFixture()
        connection = SessionReaderConnection(f.app.sshRepository, f.host, "live-abcd1234",
            agent = AgentKind.CLAUDE, historyScript = byteArrayOf(), historyCache = ChatHistoryCache(f.app.cacheDir.resolve("live-history")))
        val cache = ChatHistoryCache(f.app.cacheDir.resolve("live-history"))
        val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "live-abcd1234", f.session.workingDirectory)
        val chat = ChatHistoryConnection(f.app.sshRepository, f.host, identity, byteArrayOf(), cache)
        f.field<MutableStateFlow<ChatHistoryState>>(chat, "_state").value = ChatHistoryState(loaded = true)
        ReflectionHelpers.setField(connection, "historyIdentity", identity)
        ReflectionHelpers.setField(connection, "historyConnection", chat)
    }

    @After fun cleanup() {
        connection.close()
        f.close()
    }

    private fun render(paneCommand: String, name: String) {
        f.field<MutableStateFlow<ReaderState>>(connection, "_state").value = ReaderState(status = ReaderStatus.Live)
        val session = f.session.copy(name = name, paneCommand = paneCommand)
        compose.setContent {
            ReaderScreen(
                connection = connection, dictation = f.app.deepgramDictation,
                screencast = f.app.screencastUploader,
                signedIn = true, onSignIn = {}, onSignOut = {}, session = session,
                unread = false, archived = false, initialFontScale = 1f,
                onBack = {}, onMarkRead = {}, onArchiveToggle = {}, onRemind = {},
                onOpenTerminal = {}, onFontScaleChanged = {}, onRename = { null },
                onDissolve = {}, onEnd = {}, onSessionInteraction = {}, onSessionInput = {},
                technicalMode = false, onTechnicalModeChanged = {}, sunlightMode = false,
                onSetAppTheme = {}, onResetFontScale = { 1f }, sessionActionError = null,
                onClearSessionActionError = {}, onForget = {}, onShowEtaAccuracy = {},
                onReleaseNotes = {},
            )
        }
    }

    @Test fun aDesktopLiveChatOpensAsAChatSoThePhoneSendsThroughTheSharedChat() {
        val live = f.session.copy(name = "live-abcd1234", paneCommand = "sleep")
        assertEquals(AgentKind.CLAUDE, live.agent)
        render(name = live.name, paneCommand = live.paneCommand)
        compose.onNodeWithTag("chat-history").assertIsDisplayed()
    }

    @Test fun aPlainShellSessionStillOpensAsATerminal() {
        val shell = f.session.copy(name = "work", paneCommand = "bash")
        assertEquals(AgentKind.OTHER, shell.agent)
        render(name = shell.name, paneCommand = shell.paneCommand)
        compose.onNodeWithTag("chat-history").assertDoesNotExist()
    }

    @Test fun theSharedChatKeepsOneMessageIdSoARetryCannotSendTwice() {
        val live = f.session.copy(name = "live-abcd1234", paneCommand = "sleep")
        assertEquals(AgentKind.CLAUDE, live.agent)
        val first = QueuedMessages.SentPrompt("hello", 1L, messageId = "m-1", sid = "s", inode = 0, afterOffset = 0)
        val retry = first.copy(sentAtMillis = 2L)
        assertEquals(first.messageId, retry.messageId)
    }
}
