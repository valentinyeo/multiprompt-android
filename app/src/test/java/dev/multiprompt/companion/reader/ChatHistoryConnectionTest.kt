package dev.multiprompt.companion.reader

import android.app.Application
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.security.SecretStore
import dev.multiprompt.companion.ssh.SshRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    fun resyncWithoutConnectionPreservesUnconfirmedMessages() = runBlocking {
        SessionReaderConnection(repository(), host, "tmux", agent = AgentKind.CLAUDE, historyScript = byteArrayOf(),
            historyCache = ChatHistoryCache(temporary.newFolder())).use { reader ->
            val text = "Any idea when the visual update of the inbox is coming?"
            assertTrue(reader.sendPrompt(text))
            val before = ChatHistory().displayMessages(reader.state.value.sentRecord)
            assertEquals(listOf(text), before.map { it.text })
            assertTrue(before.single().pending)
            assertFalse(reader.resyncChat())
            assertEquals(listOf(text), reader.state.value.sentRecord.map { it.text })
            assertEquals(listOf(text), reader.state.value.sentPrompts)
            assertTrue(ChatHistory().displayMessages(reader.state.value.sentRecord).single().pending)
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
}
