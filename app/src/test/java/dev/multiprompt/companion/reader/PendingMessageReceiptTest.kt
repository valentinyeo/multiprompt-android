package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import java.io.File
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingMessageReceiptTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun realReaderPreservesQueueAttachmentsAndExactRemovalReceipts() {
        val home = temporary.newFolder("home")
        val file = File(home, ".claude/projects/-pending/pending.jsonl").apply { parentFile!!.mkdirs() }
        val prompt = "Please check the next change"
        val at = Instant.now()
        fun event(operation: String, text: String, second: Long) = JSONObject()
            .put("type", "queue-operation").put("operation", operation).put("content", text)
            .put("timestamp", at.plusSeconds(second).toString()).toString() + "\n"
        file.writeText(event("enqueue", prompt, 0) + JSONObject().put("type", "attachment")
            .put("uuid", "pending-request").put("timestamp", at.toString())
            .put("attachment", JSONObject().put("type", "queued_command").put("prompt", prompt)).toString() + "\n")
        ChatHistoryProcess("pending", "/pending", home).use { reader ->
            reader.poll()
            val queued = reader.history.rows.single { it.text == prompt }
            assertTrue("Queued transcript attachments must stay pending", queued.json().optBoolean("queued"))
            assertTrue(reader.history.messages.single().pending)
            file.appendText(event("remove", "A different prompt", 1))
            reader.poll()
            assertTrue("Unrelated receipt must not accept this message", reader.history.messages.single().pending)
            file.appendText(event("remove", prompt, 2))
            reader.poll()
            val receipts = reader.history.rows.map { it.json() }.mapNotNull { it.optJSONObject("settlement") }
            assertEquals(listOf("enqueue", "remove", "remove"), receipts.map { it.getString("operation") })
            assertEquals(listOf(prompt, "A different prompt", prompt), receipts.map { it.getString("content") })
            assertEquals(listOf(prompt), reader.history.messages.map { it.text })
            assertTrue(!reader.history.messages.single().pending)
            val cache = ChatHistoryCache(temporary.newFolder("cache"))
            cache.save("pending-proof", reader.history)
            assertEquals(reader.history, cache.restore("pending-proof"))
        }
    }

    @Test fun localEchoRequiresFreshExactAcceptanceAndTranscriptAlsoAccepts() {
        val now = Instant.parse("2026-10-26T12:00:00Z").toEpochMilli()
        val text = "Please check the next change with the same shared prefix in this message"
        val sent = listOf(QueuedMessages.SentPrompt(text, now))
        fun receipt(operation: String, content: String, at: Long, offset: Long) = ChatHistoryProtocol.parseRow(JSONObject()
            .put("role", "assistant").put("text", "").put("timestamp", Instant.ofEpochMilli(at).toString())
            .put("offset", offset).put("end", offset + 1)
            .put("settlement", JSONObject().put("operation", operation).put("content", content)))
        fun display(rows: List<ChatHistoryProtocol.Row>) = ChatHistory(rows = rows)
            .displayMessages("", AgentKind.CLAUDE, sent, now + 10).single()
        assertTrue(display(emptyList()).pending)
        assertTrue(display(listOf(receipt("remove", text, now - 1, 0))).pending)
        assertTrue(display(listOf(receipt("remove", text.take(45), now + 1, 0))).pending)
        val accepted = receipt("remove", text, now + 1, 0)
        assertTrue(!display(listOf(accepted)).pending)
        assertEquals(text, display(listOf(accepted)).text)
        assertTrue(display(listOf(accepted, receipt("enqueue", text, now + 2, 1))).pending)
        assertTrue(!display(listOf(receipt("popAll", "", now + 1, 0))).pending)
        val user = ChatHistoryProtocol.Row("user", text, "recorded", Instant.ofEpochMilli(now + 1).toString(), 0, 1)
        assertTrue(!display(listOf(user)).pending)
        val directory = temporary.newFolder("old-cache")
        val cache = ChatHistoryCache(directory)
        cache.save("session", ChatHistory("sid", 1, 1, rows = listOf(user)))
        val file = directory.listFiles()!!.single()
        val lines = file.readLines()
        file.writeText(JSONObject(lines.first()).put("version", 2).toString() + "\n" + lines.drop(1).joinToString("\n", postfix = "\n"))
        assertEquals(null, cache.restore("session"))
    }
}
