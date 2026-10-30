package dev.multiprompt.companion.reader

import java.io.File
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingMessageReceiptTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun realReaderKeepsAttachmentTextAndCacheHistoryWithoutInferringDelivery() {
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
            assertEquals(listOf(prompt), reader.history.messages.map { it.text })
            assertFalse(reader.history.messages.single().pending)
            file.appendText(event("remove", "A different prompt", 1) + event("remove", prompt, 2))
            reader.poll()
            val receipts = reader.history.rows.map { it.json() }.mapNotNull { it.optJSONObject("settlement") }
            assertEquals(listOf("enqueue", "remove", "remove"), receipts.map { it.getString("operation") })
            assertEquals(listOf(prompt, "A different prompt", prompt), receipts.map { it.getString("content") })
            assertEquals(listOf(prompt), reader.history.messages.map { it.text })
            assertFalse(reader.history.messages.single().pending)
            val cache = ChatHistoryCache(temporary.newFolder("cache"))
            cache.save("pending-proof", reader.history)
            assertEquals(reader.history, cache.restore("pending-proof"))
        }
    }

    @Test fun settlementRowsWithVisibleTextRemainHistory() {
        val row = ChatHistoryProtocol.Row("assistant", "Receipt text", "id", "", 0, 1,
            settlement = ChatHistoryProtocol.Settlement(ChatHistoryProtocol.QueueOperation.REMOVE, "other"))
        assertEquals(listOf("Receipt text"), ChatHistory(rows = listOf(row)).messages.map { it.text })
    }
}
