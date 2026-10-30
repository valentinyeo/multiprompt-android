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
        SessionReaderConnection(repository(), host, "tmux", historyScript = byteArrayOf(),
            historyCache = ChatHistoryCache(temporary.newFolder())).use { reader ->
            val text = "Any idea when the visual update of the inbox is coming?"
            assertTrue(reader.sendPrompt(text))
            val now = System.currentTimeMillis()
            val before = ChatHistory().displayMessages("", AgentKind.CLAUDE, reader.state.value.sentRecord, now)
            assertEquals(listOf(text), before.map { it.text })
            assertTrue(before.single().pending)
            assertFalse(reader.resyncChat())
            assertEquals(listOf(text), reader.state.value.sentRecord.map { it.text })
            assertEquals(listOf(text), reader.state.value.sentPrompts)
            assertTrue(ChatHistory().displayMessages("", AgentKind.CLAUDE, reader.state.value.sentRecord, now).single().pending)
        }
    }

    @Test
    fun resyncConfirmsOnlyObservedNativeAndSettledManagedMessages() {
        val delivered = QueuedMessages.SentPrompt("delivered", 1_700_000_000_000)
        val pending = delivered.copy(text = "pending")
        val queued = delivered.copy(text = "queued")
        val managed = delivered.copy(text = "managed", messageId = "id", delivery = QueuedMessages.Delivery.QUEUED)
        val history = ChatHistory(rows = listOf(
            ChatHistoryProtocol.Row("user", "delivered", "one", "2023-11-14T22:13:20Z", 1, 2),
            ChatHistoryProtocol.Row("user", "queued", "two", "2023-11-14T22:13:20Z", 3, 4, queued = true),
        ), deliveries = mapOf("id" to QueuedMessages.Delivery.DELIVERED))
        for (agent in listOf(AgentKind.CODEX, AgentKind.HAX)) {
            val sent = listOf(delivered, pending, queued, managed)
            assertEquals(listOf(pending, queued), unconfirmedAfterResync(history, sent))
            assertTrue(history.displayMessages("", agent, sent, delivered.sentAtMillis).any { it.text == "pending" && it.pending })
        }
        assertEquals(listOf(pending), unconfirmedAfterResync(ChatHistory(), listOf(pending)))
        val previous = history.copy(sid = "session", inode = 1)
        assertTrue(unconfirmedAfterResync(ChatHistory(sid = "session", inode = 1), listOf(delivered), previous).isEmpty())
        assertEquals(listOf(delivered), unconfirmedAfterResync(ChatHistory(sid = "other", inode = 1), listOf(delivered), previous))
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
