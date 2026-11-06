package dev.multiprompt.companion.reader

import android.app.Application
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.security.SecretStore
import dev.multiprompt.companion.ssh.SshRepository
import kotlinx.coroutines.runBlocking
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
class ChatHistoryPrefetchTest {
    @get:Rule val temporary = TemporaryFolder()
    private val host = HostProfile(id = "host", label = "invalid", hostname = "", port = 0,
        username = "user", keySecretId = "missing")
    private val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "tmux", "/cwd")

    private fun row(role: String, text: String, offset: Long, end: Long) =
        JSONObject().put("role", role).put("text", text).put("id", "row$offset").put("timestamp", "")
            .put("offset", offset).put("end", end).toString()

    private fun reply(id: Long, answered: Boolean): String {
        val rows = listOf(row("user", "question", 0, 10)) + if (answered) listOf(row("assistant", "answer", 10, 20)) else emptyList()
        val end = if (answered) 20 else 10
        val header = listOf("@mp", id, "history", "ok", "sid1", 0, end, 1, 0, "claude", "tmux", 1, "a".repeat(64), "tail", 0).joinToString("\t")
        return JSONObject().put("id", id).put("wire", (listOf(header) + rows + TranscriptProtocol.END).joinToString("\n") + "\n").toString()
    }

    @Test
    fun backgroundRefreshFillsTheCacheSoAChatOpensWithItsMessages() = runBlocking {
        val cache = ChatHistoryCache(temporary.newFolder())
        val requests = mutableListOf<JSONObject>()
        var answered = false
        val exchange: suspend (String) -> String = { line ->
            val request = JSONObject(line).also { requests.add(it) }
            reply(request.getLong("id"), answered)
        }
        ChatHistoryPrefetch.refresh(cache, host, listOf(identity), exchange)
        val key = ChatHistoryConnection.cacheKey(host, identity)
        assertEquals(listOf("question"), cache.peek(key)?.rows?.map { it.text })
        assertEquals("", requests[0].getString("cached_sid"))

        answered = true
        ChatHistoryPrefetch.refresh(cache, host, listOf(identity), exchange)
        assertEquals("sid1", requests[1].getString("cached_sid"))
        assertEquals(listOf("question", "answer"), cache.peek(key)?.rows?.map { it.text })

        ChatHistoryConnection(SshRepository(SecretStore(RuntimeEnvironment.getApplication())), host, identity, byteArrayOf(), cache).use { connection ->
            assertFalse(connection.state.value.loaded)
            connection.start()
            assertTrue(connection.state.value.loaded)
            assertEquals(cache.peek(key), connection.state.value.history)
        }
    }

    @Test
    fun aFailedRefreshKeepsTheSavedMessages() = runBlocking {
        val cache = ChatHistoryCache(temporary.newFolder())
        val key = ChatHistoryConnection.cacheKey(host, identity)
        val saved = ChatHistory("sid", 1, 100, 0, 1, "a".repeat(64), rows = listOf(ChatHistoryProtocol.Row("user", "kept", "id", "", 0, 100)))
        cache.save(key, saved)
        val failure = runCatching {
            ChatHistoryPrefetch.refresh(cache, host, listOf(identity)) { throw java.io.IOException("offline") }
        }
        assertTrue(failure.isFailure)
        assertEquals(saved, cache.peek(key))
    }

    @Test
    fun savedMemoryMatchesWhatTheDiskKeeps() {
        val directory = temporary.newFolder()
        val cache = ChatHistoryCache(directory, sessionBytes = 1_200)
        val key = ChatHistoryConnection.cacheKey(host, identity)
        val rows = (0 until 6).map { ChatHistoryProtocol.Row("user", "x".repeat(60) + it, "id$it", "", it * 10L, it * 10L + 10) }
        val question = PendingQuestion.parse("id5", 0, JSONObject().put("question", "Pick").put("options", org.json.JSONArray()), "claude")
        cache.save(key, ChatHistory("sid", 1, 60, 0, 1, "a".repeat(64), rows = rows, pendingQuestions = listOf(question)))
        val memory = requireNotNull(cache.peek(key))
        val disk = requireNotNull(ChatHistoryCache(directory, sessionBytes = 1_200).restore(key))
        assertTrue(memory.rows.size < rows.size)
        assertEquals(disk.rows.map { it.text }, memory.rows.map { it.text })
        assertEquals(disk.start, memory.start)
        assertTrue(memory.pendingQuestions.isEmpty())
    }
}
