package dev.multiprompt.companion.reader

import android.app.Application
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.security.SecretStore
import dev.multiprompt.companion.ssh.SshRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatHistoryConnectionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val host = HostProfile(id = "host", label = "invalid", hostname = "", port = 0,
        username = "user", keySecretId = "missing")
    private fun repository() = SshRepository(SecretStore(RuntimeEnvironment.getApplication()))

    @Test
    fun restoresPrivateCacheAndRetainsItWhenConnectionFails() = runBlocking {
        val cache = ChatHistoryCache(temporary.newFolder())
        val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "tmux", "/cwd")
        val row = ChatHistoryProtocol.Row("user", "Cached conversation € ✔", "id", "", 0, 100)
        val history = ChatHistory("sid", 1, 100, 0, 1, "a".repeat(64), rows = listOf(row))
        val key = listOf(host.id, host.hostname, host.port.toString(), host.username,
            identity.agent.name, identity.tmux, identity.cwd).joinToString("\u0000")
        cache.save(key, history)
        ChatHistoryConnection(repository(), host, identity, byteArrayOf(), cache).use { connection ->
            assertFalse(connection.state.value.loaded)
            connection.start()
            val failed = withTimeout(10_000L) { connection.state.first { it.retrying } }
            assertTrue(failed.loaded)
            assertEquals(history, failed.history)
            assertEquals(history.messages, failed.history.messages)
        }
    }

    @Test
    fun warmReaderStartsHistoryForCurrentAgentAndReplacesChangedIdentity() {
        SessionReaderConnection(repository(), host, "tmux", historyScript = byteArrayOf(),
            historyCache = ChatHistoryCache(temporary.newFolder())).use { reader ->
            val claude = reader.chatHistory(AgentKind.CLAUDE, "/cwd")
            assertSame(claude, reader.chatHistory(AgentKind.CLAUDE, "/cwd"))
            val codex = reader.chatHistory(AgentKind.CODEX, "/cwd")
            assertNotSame(claude, codex)
            assertSame(codex, reader.chatHistory(AgentKind.CODEX, "/cwd"))
            assertNotSame(codex, reader.chatHistory(AgentKind.CODEX, "/other"))
        }
    }

    @Test
    fun nativeSendWithoutFreshConnectionKeepsDraftUnsent() = runBlocking {
        SessionReaderConnection(repository(), host, "tmux", agent = AgentKind.CLAUDE, historyScript = byteArrayOf(),
            historyCache = ChatHistoryCache(temporary.newFolder())).use { reader ->
            val text = "Any idea when the visual update of the inbox is coming?"
            assertTrue(reader.sendPrompt(text) is PromptAdmission.Rejected)
            assertFalse(reader.resyncChat())
            assertTrue(reader.state.value.sentRecord.isEmpty())
        }
    }

    @Test
    fun resyncPreservesUnkeyedAndHeldMessagesUntilServerSettlesTheirIds() {
        val native = QueuedMessages.SentPrompt("same", 1)
        val first = native.copy(messageId = "first", delivery = QueuedMessages.Delivery.QUEUED)
        val second = first.copy(messageId = "second")
        val history = ChatHistory(rows = listOf(
            ChatHistoryProtocol.Row("user", "same", "one", "2023-11-14T22:13:20Z", 1, 2),
            ChatHistoryProtocol.Row("assistant", "Done", "two", "", 3, 4),
        ), held = listOf(second), deliveries = mapOf(
            "first" to QueuedMessages.Delivery.DELIVERED,
            "second" to QueuedMessages.Delivery.CANCELLED,
        ))
        assertEquals(listOf(native), unconfirmedAfterResync(history, listOf(native, first, second)))
        assertEquals(listOf(first), unconfirmedAfterResync(ChatHistory(), listOf(first)))
        assertEquals(emptyList<QueuedMessages.SentPrompt>(), unconfirmedAfterResync(
            ChatHistory(deliveries = mapOf("first" to QueuedMessages.Delivery.CANCELLED)), listOf(first)))
    }

    @Test
    fun liveRequestsFollowThePaneWhileArchivesKeepTheirExactSession() {
        val history = ChatHistory(sid = "cached", inode = 9, offset = 42)
        val live = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "pane", "/cwd", "saved")
        val request = org.json.JSONObject(ChatHistoryProtocol.request(1, live, history))
        assertTrue(request.getBoolean("follow"))
        assertEquals("saved", request.getString("sid"))
        assertEquals("cached", request.getString("cached_sid"))
        assertEquals(42L, request.getLong("offset"))
        val archive = org.json.JSONObject(ChatHistoryProtocol.request(2, live.copy(tmux = ""), history))
        assertFalse(archive.getBoolean("follow"))
        assertEquals("saved", archive.getString("sid"))
        assertEquals("cached", archive.getString("cached_sid"))
    }

    @Test
    fun resyncRequestsTheWholeTailFromTheSession() {
        val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "tmux", "/cwd")
        val request = org.json.JSONObject(ChatHistoryProtocol.request(7, identity, ChatHistory()))
        assertEquals("", request.getString("cached_sid"))
        assertEquals(0L, request.getLong("offset"))
        assertEquals(0L, request.getLong("start"))
    }
    @Test
    fun resetSeedRetainsOnlySameSessionTerminalFactsAcrossStalePages() {
        val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "tmux", "/cwd")
        for (terminal in listOf(QueuedMessages.Delivery.DELIVERED, QueuedMessages.Delivery.CANCELLED)) {
            val prompt = QueuedMessages.SentPrompt("text", 1, "id", QueuedMessages.Delivery.QUEUED, "sid", 9, 10)
            val initial = ChatHistory(sid = "sid", inode = 9, offset = 30, start = 5, mtime = 7,
                probe = "a".repeat(64), rows = listOf(ChatHistoryProtocol.Row("user", "old", "row", "", 5, 10)),
                deliveries = mapOf("guess" to QueuedMessages.Delivery.QUEUED), held = listOf(prompt))
            val observed = ChatHistoryProtocol.Page("sid", 0, 30, 9, false, 7, "a".repeat(64), "tail", 0,
                emptyList(), deliveries = mapOf("id" to terminal))
            val known = initial.apply(observed)
            val seed = known.resetSeed()
            assertEquals(mapOf("id" to terminal), seed.deliveries)
            assertTrue(seed.rows.isEmpty())
            assertTrue(seed.held.isEmpty())
            val request = org.json.JSONObject(ChatHistoryProtocol.request(1, identity, seed.copy(sid = "")))
            assertTrue(request.getBoolean("follow"))
            assertEquals("", request.getString("cached_sid"))
            for (field in listOf("inode", "offset", "start", "mtime", "before")) assertEquals(0L, request.getLong(field))
            assertEquals("", request.getString("probe"))
            val stale = observed.copy(deliveries = mapOf("id" to QueuedMessages.Delivery.QUEUED), held = listOf(prompt))
            for (page in listOf(stale, stale.copy(inode = 10))) {
                val recovered = seed.apply(page)
                assertEquals(terminal, recovered.deliveries["id"])
                assertFalse(recovered.displayMessages(listOf(prompt)).single().pending)
                assertTrue(unconfirmedAfterResync(recovered, listOf(prompt)).isEmpty())
            }
            val rotated = known.apply(stale.copy(inode = 10))
            assertEquals(terminal, rotated.deliveries["id"])
            assertFalse(rotated.deliveries.containsKey("guess"))
            val other = seed.apply(stale.copy(sid = "other", inode = 10))
            assertEquals(QueuedMessages.Delivery.QUEUED, other.deliveries["id"])
            assertEquals(listOf(prompt), unconfirmedAfterResync(other, listOf(prompt)))
        }
    }

    private class FakeHost {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        @Volatile var answered = false
        private val digest = "b".repeat(64)

        private fun row(role: String, text: String, offset: Long, end: Long) =
            JSONObject().put("role", role).put("text", text).put("id", "row$offset").put("timestamp", "")
                .put("offset", offset).put("end", end).toString()

        private fun history(id: Long): String {
            val rows = listOf(row("user", "question", 0, 10)) + if (answered) listOf(row("assistant", "answer", 10, 20)) else emptyList()
            val end = if (answered) 20 else 10
            val header = listOf("@mp", id, "history", "ok", "sid1", 0, end, 1, 0, "claude", "tmux", 1, "a".repeat(64), "tail", 0).joinToString("\t")
            return JSONObject().put("id", id).put("wire", (listOf(header) + rows + TranscriptProtocol.END).joinToString("\n") + "\n").toString()
        }

        private fun result(id: Long, value: JSONObject) =
            JSONObject().put("id", id).put("message_protocol", 1).put("result", value).toString()

        suspend fun connect(block: suspend (suspend (String) -> String) -> Unit) {
            block { line ->
                if (calls.incrementAndGet() > 3_000) throw java.io.IOException("Request flood")
                val request = JSONObject(line)
                val id = request.getLong("id")
                when (request.optString("op")) {
                    "engine_info" -> JSONObject().put("id", id)
                        .put("engine_info", JSONObject().put("protocol", 1).put("generation", 53).put("sha256", digest))
                        .put("message_protocols", JSONArray().put(1)).toString()
                    "message_snapshot" -> result(id, JSONObject().put("snapshot_id", "snap").put("epoch", 1).put("seq", 0)
                        .put("items", JSONArray()).put("next_cursor", JSONObject.NULL))
                    "message_events" -> result(id, JSONObject().put("epoch", 1).put("seq", 0)
                        .put("events", JSONArray()).put("next_after", 0))
                    "" -> history(id)
                    else -> error("Unexpected request")
                }
            }
        }
    }

    @Test
    fun visibleChatKeepsUpdatingAfterAnotherReaderOfTheSameChatCloses() = runBlocking {
        val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "tmux", "/cwd")
        val gate = MessageGate()
        val fake = FakeHost()
        val other = FakeHost()
        val visible = ChatHistoryConnection(repository(), host, identity, byteArrayOf(), ChatHistoryCache(temporary.newFolder()),
            gate = gate, connectedExchange = { block -> fake.connect(block) })
        val second = ChatHistoryConnection(repository(), host, identity, byteArrayOf(), ChatHistoryCache(temporary.newFolder()),
            gate = gate, connectedExchange = { block -> other.connect(block) })
        visible.use {
            visible.select(true)
            visible.start()
            withTimeout(10_000L) { visible.state.first { it.engine != null && it.history.rows.size == 1 } }
            second.select(true)
            second.start()
            withTimeout(10_000L) { second.state.first { it.engine != null && it.history.rows.size == 1 } }
            second.close()
            fake.answered = true
            val before = fake.calls.get()
            val shown = withTimeoutOrNull(5_000L) { visible.state.first { it.history.rows.size == 2 || it.retrying } }
            delay(3_000L)
            val requests = fake.calls.get() - before
            assertTrue("visible chat sent $requests requests in about 3 s", requests < 25)
            assertNotNull("visible chat never showed the answer", shown)
            assertEquals(listOf("question", "answer"), shown!!.history.rows.map { it.text })
        }
    }

}
