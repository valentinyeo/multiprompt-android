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
import dev.multiprompt.companion.reader.MessageJournal
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
    fun theLastReplyStaysWhenTheNextMessageStartsATurn() {
        val replied = HostStream().apply(event("turn.started", null, """{"message_id":"m1"}""", "t-1"))
            .apply(event("text.delta", "a:text:0", """{"text":"Here is the answer.","done":true}"""))
            .apply(event("turn.ended", null, "{}"))
        val next = replied.apply(event("turn.started", null, """{"message_id":"m2"}""", "t-2"))
        assertEquals(listOf("Here is the answer."), next.earlier.map { it.text })
        assertEquals("t-1", next.earlier.single().turn)

        val asked = ChatHistory(sid = sid, inode = 7, offset = 300, start = 0,
            rows = listOf(ChatHistoryProtocol.Row("user", "first", "u1", "", 100, 180)))
        val second = MessageProtocol.Row("mp-abcdef0123456789", "claude", sid, "second", 2, "attempt0123456789",
            MessageProtocol.State.QUEUED, null, null, 0, 1793653981.5)
        val replica = MessageReplica(0, 0, messages = mapOf(second.id to second), host = next)
        val drawn = asked.managedMessages(replica, emptyList()).map { it.text }
        assertEquals(listOf("first", "Here is the answer.", "second"), drawn)

        val landed = asked.copy(rows = asked.rows + ChatHistoryProtocol.Row("assistant", "Here is the answer.", "a1", "", 180, 300))
        val settled = landed.managedMessages(replica, emptyList())
        assertEquals(1, settled.count { it.text == "Here is the answer." })
        assertTrue(settled.none { it.key.startsWith("@live:t-1") })
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
    fun aFailedMessageSaysNotSentWithSendAgain() {
        val failed = MessageProtocol.Row("mp-abcdef0123456789", "claude", sid, "say PONG", 1, "attempt0123456789",
            MessageProtocol.State.FAILED, null, null, 0, 1793653981.5)
        render(ChatHistoryState(ChatHistory(sid = sid, inode = 7, offset = 300), loaded = true,
            replica = MessageReplica(0, 0, messages = mapOf(failed.id to failed))))

        compose.onNodeWithText("say PONG").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Failed").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Not sent").assertIsDisplayed()
        compose.onNodeWithText("Send again").assertIsDisplayed()
        compose.onNodeWithText("Remove").assertIsDisplayed()
    }

    @Test
    fun leftoverRecoveryStateShowsNoButtons() {
        val cancelled = MessageProtocol.Row("mp-abcdef0123456789", "claude", sid, "say PONG", 1, "attempt0123456789",
            MessageProtocol.State.CANCELLED, null, null, 0, 1793653981.5)
        val unresolved = listOf(
            MessageJournal.Entry("reset-0123456789abc", "account", scope, "reset", "", null, 0, null, true),
            MessageJournal.Entry("retry-0123456789abc", "account", scope, "retry", cancelled.id, null, 1, "attempt0123456789", true),
            MessageJournal.Entry("dismiss-0123456789a", "account", scope, "dismiss", "mp-0123456789abcdef", "say PING", 1, null, true),
        )
        render(ChatHistoryState(ChatHistory(sid = sid, inode = 7, offset = 300), loaded = true,
            replica = MessageReplica(0, 0, messages = mapOf(cancelled.id to cancelled)), unresolved = unresolved))

        listOf("Check reset", "Send again", "Remove").forEach {
            assertTrue("$it must not show in a live chat", compose.onAllNodesWithText(it).fetchSemanticsNodes().isEmpty())
        }
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

    private fun accepted(position: Long, text: String, created: Double) = MessageProtocol.Row(
        "mp-%016d".format(position), "claude", sid, text, position, "attempt0123456789",
        MessageProtocol.State.ACCEPTED, null, null, 0, created)

    private fun answeredChat(): ChatHistoryState {
        val history = ChatHistory(sid = sid, inode = 7, offset = 900, start = 100, more = true, rows = listOf(
            ChatHistoryProtocol.Row("user", "Synthetic ask", "u1", "2026-11-03T13:21:09.775Z", 100, 300),
            ChatHistoryProtocol.Row("assistant", "Synthetic answer arrived", "a1", "2026-11-03T13:21:26.722Z", 600, 900),
        ))
        val older = (1L..15L).map { accepted(it, "Older synthetic prompt $it", 1793690000.0 + it * 600) }
        val newest = accepted(16, "Synthetic ask", 1793712066.72)
        return ChatHistoryState(history, loaded = true,
            replica = MessageReplica(0, 0, messages = (older + newest).associateBy { it.id }))
    }

    @Test
    fun anAnsweredLiveChatEndsWithTheReply() {
        val state = answeredChat()
        val drawn = state.history.managedMessages(state.replica, emptyList())
        assertEquals("Synthetic answer arrived", drawn.last().text)
        assertEquals(1, drawn.count { it.text == "Synthetic ask" })
        assertEquals("@shared:mp-0000000000000016", drawn.single { it.text == "Synthetic ask" }.key)
        assertTrue(drawn.none { it.text.startsWith("Older synthetic prompt") })
        render(state)
        compose.onNodeWithText("Synthetic answer arrived").assertIsDisplayed()
        capture(compose.onNodeWithTag("chat-history"), "live-chat-answered")
    }

    @Test
    fun anAcceptedSendStaysBelowTheTranscriptUntilItLands() {
        val state = answeredChat()
        val waiting = accepted(17, "Synthetic follow up", 1793712100.0)
        val replica = state.replica!!.copy(messages = state.replica!!.messages + (waiting.id to waiting))
        val drawn = state.history.managedMessages(replica, emptyList())
        assertEquals(listOf("Synthetic answer arrived", "Synthetic follow up"), drawn.takeLast(2).map { it.text })
        assertEquals("@shared:${waiting.id}", drawn.last().key)
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
