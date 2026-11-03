package dev.multiprompt.companion.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.reader.ChatHistory
import dev.multiprompt.companion.reader.ChatHistoryCache
import dev.multiprompt.companion.reader.ChatHistoryConnection
import dev.multiprompt.companion.reader.ChatHistoryProtocol
import dev.multiprompt.companion.reader.ChatHistoryState
import dev.multiprompt.companion.reader.HostBlock
import dev.multiprompt.companion.reader.HostStream
import dev.multiprompt.companion.reader.MessageProtocol
import dev.multiprompt.companion.reader.MessageReplica
import dev.multiprompt.companion.reader.ReaderState
import dev.multiprompt.companion.reader.ReaderStatus
import dev.multiprompt.companion.reader.SessionReaderConnection
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper.shadowMainLooper
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveChatStreamTest {
    private val scheduler = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(scheduler))
    private val sid = "d5339a17-2bfb-4710-a297-4c7548e0e636"
    private val scope = MessageProtocol.Scope("claude", sid, "live-1d21840")
    private lateinit var f: SessionActivityFixture
    private lateinit var connection: SessionReaderConnection

    @Before fun setup() {
        f = SessionActivityFixture()
        connection = SessionReaderConnection(f.app.sshRepository, f.host, "live-1d21840",
            agent = AgentKind.CLAUDE, historyScript = byteArrayOf(), historyCache = ChatHistoryCache(f.app.cacheDir.resolve("stream-history")),
            hostSessionId = sid)
        val chat = ChatHistoryConnection(f.app.sshRepository, f.host, identity(), byteArrayOf(), ChatHistoryCache(f.app.cacheDir.resolve("stream-history")))
        ReflectionHelpers.setField(connection, "historyIdentity", identity())
        ReflectionHelpers.setField(connection, "historyConnection", chat)
    }

    @After fun cleanup() {
        connection.close()
        f.close()
    }

    private fun identity() = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "live-1d21840", f.session.workingDirectory, sid)

    private fun events(vararg bodies: String) = JSONObject().put("id", 1).put("message_protocol", 1)
        .put("result", JSONObject().put("epoch", 0).put("seq", bodies.size).put("next_after", bodies.size)
            .put("events", org.json.JSONArray(bodies.mapIndexed { index, body ->
                JSONObject().put("seq", index + 1).put("event", JSONObject(body))
            }))).toString()

    private fun host(kind: String, item: String?, payload: String, turn: String? = "t-1") = JSONObject()
        .put("v", 1).put("sid", sid).put("turn", turn ?: JSONObject.NULL).put("kind", kind)
        .put("at", 1793653981461L).put("item", item ?: JSONObject.NULL).put("payload", JSONObject(payload)).toString()

    private fun row(state: String, text: String, position: Int, id: String = "mp-abcdef0123456789") = JSONObject()
        .put("id", id).put("provider", "claude").put("sid", sid).put("text", text).put("position", position)
        .put("attempt", "attempt0123456789").put("state", state).put("deadline", JSONObject.NULL)
        .put("receipt", JSONObject.NULL).put("epoch", 0).put("created", 1793653981.5).toString()

    private fun capturedEvents() = events(
        host("host.status", null, """{"state":"ready"}"""),
        row("accepted", "say PONG", 1),
        host("turn.started", null, """{"message_id":"mp-abcdef0123456789","text_sha256":"a"}"""),
        host("text.delta", "msg_01:text:0", """{"text":"PO","done":false}"""),
        host("text.done", "msg_01:text:0", """{"text":"PONG"}"""),
        host("turn.ended", null, """{"status":"completed","stop_reason":"end_turn"}"""),
    )

    @Test
    fun hostEventsFromTheDesktopHostParse() {
        val parsed = runCatching { MessageProtocol.events(capturedEvents(), 1, scope, 0) }
            .getOrElse { fail("a live chat's host events must parse: ${it.message}"); return }

        assertEquals(6L, parsed.next)
        assertEquals(6, parsed.events.size)
    }

    @Test
    fun hostEventsUpdateTheSentMessageAndTheLiveTail() {
        val parsed = runCatching { MessageProtocol.events(capturedEvents(), 1, scope, 0) }
            .getOrElse { fail("a live chat's host events must parse: ${it.message}"); return }

        val replica = MessageReplica(0, 0).apply(parsed)
        assertEquals(MessageProtocol.State.ACCEPTED, replica.messages.getValue("mp-abcdef0123456789").state)
        assertEquals(listOf("PONG"), replica.host.spoken.map { it.text })
        assertTrue(replica.host.spoken.single().done)
        assertFalse(replica.host.running)
    }

    @Test
    fun theLiveTailKeepsOnlyTheRunningTurn() {
        val first = HostStream().apply(event("turn.started", null, """{"message_id":"m1"}""", "t-1"))
            .apply(event("text.delta", "a:text:0", """{"text":"Hel","done":false}"""))
            .apply(event("text.delta", "a:text:0", """{"text":"Hello","done":false}"""))
        assertTrue(first.running)
        assertEquals(listOf("Hello"), first.spoken.map { it.text })

        val second = first.apply(event("turn.started", null, """{"message_id":"m2"}""", "t-2"))
        assertEquals("t-2", second.turn)
        assertTrue(second.spoken.isEmpty())
    }

    @Test
    fun aFinishedLiveReplyGivesWayToTheTranscript() {
        val history = ChatHistory(sid = sid, inode = 7, offset = 300, start = 0,
            rows = listOf(ChatHistoryProtocol.Row("assistant", "Still here.", "a1", "", 200, 300)))
        val replica = MessageReplica(0, 0, host = HostStream("t-1", running = false,
            blocks = listOf(HostBlock("x:text:0", "text", "Still here.", true))))

        val drawn = history.managedMessages(replica, emptyList())
        assertEquals(listOf("Still here."), drawn.filter { it.role == "assistant" }.map { it.text })
        assertTrue(drawn.none { it.key.startsWith("@live:") })

        val streaming = replica.copy(host = replica.host.copy(running = true,
            blocks = listOf(HostBlock("y:text:0", "text", "Still here. More to come", false))))
        val growing = history.managedMessages(streaming, emptyList()).filter { it.key.startsWith("@live:") }
        assertEquals(listOf("Still here. More to come"), growing.map { it.text })
        assertTrue(growing.single().pending)
    }

    @Test
    fun aLiveChatDrawsTheStreamedReplyAndNoAcceptedLabel() {
        val history = ChatHistory(sid = sid, inode = 7, offset = 300, start = 0,
            rows = listOf(ChatHistoryProtocol.Row("user", "say PONG", "u1", "", 100, 180)))
        val delivered = MessageProtocol.Row("mp-abcdef0123456789", "claude", sid, "say PONG", 1, "attempt0123456789",
            MessageProtocol.State.ACCEPTED, null, null, 0, 1793653981.5)
        val queued = delivered.copy(id = "mp-abcdef012345678a", position = 2, state = MessageProtocol.State.QUEUED, text = "hello?")
        val replica = MessageReplica(0, 0, messages = mapOf(delivered.id to delivered, queued.id to queued),
            host = HostStream("t-1", running = true, blocks = listOf(HostBlock("x:text:0", "text", "PONG", false))))
        render(ChatHistoryState(history, loaded = true, replica = replica))

        compose.onNodeWithText("PONG").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Accepted").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Queued").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Delivered").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aFailedMessageStillSaysSo() {
        val failed = MessageProtocol.Row("mp-abcdef0123456789", "claude", sid, "say PONG", 1, "attempt0123456789",
            MessageProtocol.State.FAILED, null, null, 0, 1793653981.5)
        render(ChatHistoryState(ChatHistory(sid = sid, inode = 7, offset = 300), loaded = true,
            replica = MessageReplica(0, 0, messages = mapOf(failed.id to failed))))

        compose.onNodeWithText("Failed").assertIsDisplayed()
    }

    @Test
    fun theLiveChatProofShowsTheReplyAndNoSendLabel() {
        val history = ChatHistory(sid = sid, inode = 7, offset = 300, start = 0,
            rows = listOf(ChatHistoryProtocol.Row("user", "say PONG", "u1", "", 100, 180)))
        val replica = MessageReplica(0, 0, host = HostStream("t-1", running = true,
            blocks = listOf(HostBlock("x:text:0", "text", "PONG", false))))
        render(ChatHistoryState(history, loaded = true, replica = replica))
        compose.onNodeWithText("PONG").assertIsDisplayed()
        capture(compose.onNodeWithTag("chat-history"), "live-chat-pong")
    }

    private fun event(kind: String, item: String?, payload: String, turn: String? = null) =
        MessageProtocol.HostEvent(kind, turn, item, 1L, JSONObject(payload).optString("text").ifEmpty { null },
            JSONObject(payload).optBoolean("done") || kind.endsWith(".done"), JSONObject(payload).optString("state").ifEmpty { null })

    private fun capture(node: SemanticsNodeInteraction, name: String) {
        val view = (node.fetchSemanticsNode().root as ViewRootForTest).view.rootView
        val attachInfo = ReflectionHelpers.getField<Any>(view, "mAttachInfo")
        val hardwareAccelerated = view.isHardwareAccelerated
        ReflectionHelpers.setField(attachInfo, "mHardwareAccelerated", false)
        val redraw = CoroutineScope(UnconfinedTestDispatcher(scheduler)).launch {
            while (isActive) {
                delay(16)
                shadowMainLooper().idleFor(Duration.ofMillis(32))
                view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
                view.viewTreeObserver.dispatchOnDraw()
                shadowMainLooper().idle()
            }
        }
        val bitmap = try {
            node.captureToImage().asAndroidBitmap()
        } finally {
            redraw.cancel()
            ReflectionHelpers.setField(attachInfo, "mHardwareAccelerated", hardwareAccelerated)
        }
        val output = File("build/reports/igsh-1547/$name.png")
        checkNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun render(state: ChatHistoryState) {
        val chat: ChatHistoryConnection = f.field(connection, "historyConnection")
        f.field<MutableStateFlow<ChatHistoryState>>(chat, "_state").value = state
        f.field<MutableStateFlow<ReaderState>>(connection, "_state").value = ReaderState(status = ReaderStatus.Live)
        compose.setContent {
            ReaderScreen(
                connection = connection, dictation = f.app.deepgramDictation,
                screencast = f.app.screencastUploader,
                signedIn = true, onSignIn = {}, onSignOut = {}, session = f.session.copy(name = "live-1d21840", paneCommand = "sleep"),
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
}
