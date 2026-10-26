package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import java.io.File
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatHistoryMergeTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun event(index: Int, role: String, text: String, id: String = "message-$index"): String = JSONObject()
        .put("type", role).put("uuid", "event-$index-${text.hashCode()}")
        .put("timestamp", Instant.ofEpochSecond(1_700_000_000L + index).toString())
        .put("message", JSONObject().put("id", id).put("role", role).put("content", text)).toString() + "\n"

    @Test
    fun pagesRealReaderFromTailToFirstAndMergesStreamedDeltasExactlyOnce() {
        val home = temporary.newFolder("home")
        val transcript = File(home, ".claude/projects/-synthetic/synthetic.jsonl").apply { parentFile!!.mkdirs() }
        val expected = (0 until 720).map { index ->
            val text = "Message $index € ✔ " + "body ".repeat(260)
            if (index % 2 == 1) text + "\nSecond fragment $index" else text
        }.toMutableList()
        transcript.bufferedWriter().use { writer ->
            for (index in expected.indices) {
                val role = if (index % 2 == 0) "user" else "assistant"
                writer.write(event(index, role, expected[index].substringBefore("\n")))
                if (role == "assistant") {
                    writer.write(JSONObject().put("type", "assistant")
                        .put("message", JSONObject().put("role", "assistant").put("id", "message-$index")
                            .put("content", JSONArray().put(JSONObject().put("type", "tool_use")
                                .put("id", "tool-$index").put("name", "Bash")
                                .put("input", JSONObject().put("command", "true"))))).toString() + "\n")
                    writer.write(event(index, role, "Second fragment $index"))
                }
            }
        }
        ChatHistoryProcess("synthetic", "/synthetic", home).use { client ->
            val history = client.all()
            assertTrue(client.pages > 1)
            assertEquals(720, history.messages.size)
            assertEquals((0 until 720).map { "message-$it" }, history.messages.map { it.requestId })
            assertEquals(expected, history.messages.map { it.text })
            assertEquals(expected.first(), history.messages.first().text)
            assertEquals(720, history.messages.map { it.key }.toSet().size)
            assertTrue(history.messages.zipWithNext().all { (a, b) -> a.offset < b.offset })
            val keys = history.messages.map { it.key }
            transcript.appendText(event(719, "assistant", "Final streamed fragment"))
            expected[719] += "\nFinal streamed fragment"
            for (index in 720 until 726) {
                val text = "New message $index € ✔"
                transcript.appendText(event(index, if (index % 2 == 0) "user" else "assistant", text))
                expected.add(text)
            }
            client.poll()
            assertEquals(expected, client.history.messages.map { it.text })
            assertEquals(keys, client.history.messages.take(720).map { it.key })
            val settled = client.history
            client.poll()
            assertEquals(settled.messages, client.history.messages)
            assertEquals(settled.rows, client.history.rows)
            transcript.appendText(buildString {
                for (index in 726 until 1526) {
                    val text = "Catch-up $index " + "€ ✔ body ".repeat(350)
                    append(event(index, if (index % 2 == 0) "user" else "assistant", text))
                    expected.add(text)
                }
            })
            client.poll()
            assertTrue(client.history.more)
            while (client.history.more) client.poll()
            assertEquals(expected, client.history.messages.map { it.text })
            transcript.appendText(event(1526, "user", "Incomplete line € ✔").trimEnd('\n'))
            client.poll()
            assertEquals(expected, client.history.messages.map { it.text })
            transcript.appendText("\n")
            client.poll()
            assertEquals(expected + "Incomplete line € ✔", client.history.messages.map { it.text })
        }
    }

    @Test
    fun codexUsesTheSameProtocolAndSourceOffsets() {
        val home = temporary.newFolder("codex-home")
        val transcript = File(home, ".codex/sessions/2026/rollout-test-codex-id.jsonl").apply { parentFile!!.mkdirs() }
        transcript.writeText((0 until 650).joinToString("") { index ->
            JSONObject().put("type", "response_item").put("timestamp", Instant.ofEpochSecond(index.toLong()).toString())
                .put("payload", JSONObject().put("type", "message").put("role", if (index % 2 == 0) "user" else "assistant")
                    .put("content", JSONArray().put(JSONObject().put("type", "output_text")
                        .put("text", "Codex $index " + "€ ".repeat(500))))).toString() + "\n"
        })
        ChatHistoryProcess("codex-id", "/synthetic", home, AgentKind.CODEX).use { client ->
            val messages = client.all().messages
            assertEquals(650, messages.size)
            assertEquals((0 until 650).map { "Codex $it " + "€ ".repeat(500) }, messages.map { it.text })
        }
    }

    private fun row(offset: Long, text: String, id: String = "id") =
        ChatHistoryProtocol.Row("assistant", text, id, "2026-01-01T00:00:00Z", offset, offset + 10)

    private fun page(mode: String, rows: List<ChatHistoryProtocol.Row>, base: Long = 0, end: Long = 10,
        oldest: Long = 0, sid: String = "sid", inode: Long = 1) =
        ChatHistoryProtocol.Page(sid, base, end, inode, false, 1, "a".repeat(64), mode, oldest, rows)

    @Test
    fun replacesCumulativeFragmentsDeduplicatesRangesAndResetsIdentity() {
        val initial = ChatHistory().apply(page("tail", listOf(row(0, "Hello"))))
        val streamed = initial.apply(page("delta", listOf(row(10, "Hello € ✔")), base = 10, end = 20))
        assertEquals(listOf("Hello € ✔"), streamed.messages.map { it.text })
        assertEquals(initial.messages.single().key, streamed.messages.single().key)
        assertEquals("Other", streamed.apply(page("tail", listOf(row(0, "Other")), sid = "other")).messages.single().text)
        assertEquals("Rotated", streamed.apply(page("tail", listOf(row(0, "Rotated")), inode = 2)).messages.single().text)
        assertThrows(IllegalArgumentException::class.java) { streamed.apply(page("delta", emptyList(), base = 10)) }
        val tail = ChatHistory().apply(page("tail", listOf(row(20, "tail", "tail")), base = 20, end = 30, oldest = 20))
        val full = tail.apply(page("older", listOf(row(0, "first", "first")), end = 20))
        assertEquals(listOf("first", "tail"), full.messages.map { it.text })
        assertEquals(tail.offset, full.offset)
        assertEquals(tail.probe, full.probe)
    }

    @Test
    fun cacheFromUnboundTranscriptDiscoveryIsNeverShownAfterUpgrade() {
        val directory = temporary.newFolder("legacy-cache")
        val cache = ChatHistoryCache(directory)
        cache.save("host/session-a", ChatHistory().apply(page("tail", listOf(row(0, "Wrong session B")))))
        val file = directory.listFiles()!!.single()
        val lines = file.readLines()
        file.writeText(JSONObject(lines.first()).put("version", 1).toString() + "\n" +
            lines.drop(1).joinToString("\n", postfix = "\n"))
        assertNull("Previously guessed transcripts must be fetched again using process identity", cache.restore("host/session-a"))
    }

    @Test
    fun boundedPrivateCacheRestoresCursorAndEvictsOldSessionsWithoutPlaintextNames() {
        val directory = temporary.newFolder("cache")
        val cache = ChatHistoryCache(directory, sessionBytes = 4096, totalBytes = 7000)
        val rows = (0 until 50).map { row(it * 10L, "secret € " + "x".repeat(100), "id-$it") }
        val history = ChatHistory().apply(page("tail", rows, end = 500))
        cache.save("host/tmux", history)
        val restored = requireNotNull(cache.restore("host/tmux"))
        assertEquals(history.offset, restored.offset)
        assertEquals(history.probe, restored.probe)
        assertTrue(restored.start > 0)
        assertTrue(restored.messages.size < history.messages.size)
        assertEquals(history.messages.last(), restored.messages.last())
        assertTrue(directory.listFiles()!!.all { it.length() <= 4096 && !it.name.contains("host") })
        directory.listFiles()!!.forEach { it.setLastModified(1) }
        cache.save("second", history.copy(sid = "second"))
        cache.save("third", history.copy(sid = "third"))
        assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 7000)
        assertNull(cache.restore("host/tmux"))
        assertNotNull(cache.restore("third"))
        directory.listFiles()!!.forEach { it.writeText("not json") }
        assertNull(cache.restore("third"))
        assertNull(cache.restore("missing"))
    }

    @Test
    fun missingAndMalformedRepliesCannotBecomeConversationText() {
        val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "safe")
        val wire = "@mp\t1\thistory\tmissing\t-\t0\tmissing\n@@MP_END@@\n"
        assertNull(ChatHistoryProtocol.parse(JSONObject().put("id", 1).put("wire", wire).toString(), 1, identity))
        assertThrows(IllegalArgumentException::class.java) {
            ChatHistoryProtocol.parse(JSONObject().put("id", 2).put("wire", wire).toString(), 1, identity)
        }
        val query = JSONObject(ChatHistoryProtocol.request(1, identity.copy(tmux = "x'; unsafe", cwd = "/a\n€"), ChatHistory()))
        assertEquals("x'; unsafe", query.getString("tmux"))
        assertEquals("/a\n€", query.getString("cwd"))
        assertFalse(ChatHistoryProtocol.COMMAND.contains("safe"))
        assertTrue(query.getBoolean("no_jump"))
    }
}
