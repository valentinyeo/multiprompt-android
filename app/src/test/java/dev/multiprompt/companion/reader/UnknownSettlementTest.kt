package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnknownSettlementTest {
    private val sid = "9d1c2b34-5e6f-4a7b-8c9d-0a1b2c3d4e5f"
    private val probe = "b".repeat(64)
    private val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "future-op-1f4c", "/home/me/multiprompt", sid)

    private fun row(offset: Long, role: String, text: String): JSONObject = JSONObject()
        .put("role", role).put("text", text).put("id", "row-$offset").put("timestamp", "2026-11-24T10:0$offset:00.000Z")
        .put("offset", offset).put("end", offset + 10)

    private fun settlement(offset: Long, operation: String, content: String): JSONObject = JSONObject()
        .put("role", "assistant").put("text", "").put("id", "").put("timestamp", "2026-11-24T10:0$offset:00.000Z")
        .put("offset", offset).put("end", offset + 10)
        .put("settlement", JSONObject().put("operation", operation).put("content", content))

    private fun page(id: Long, rows: List<JSONObject>): ChatHistoryProtocol.Page {
        val header = listOf("@mp", id, "history", "ok", sid, 0, 60, 1278069, 0, "claude", "future-op-1f4c",
            1793653993390199707L, probe, "tail", 0).joinToString("\t")
        val wire = (listOf(header) + rows.map { it.toString() } + TranscriptProtocol.END).joinToString("\n") + "\n"
        return requireNotNull(ChatHistoryProtocol.parse(JSONObject().put("id", id).put("wire", wire).toString(), id, identity))
    }

    @Test
    fun aFutureSettlementOperationIsSkippedAndTheRestOfTheHistoryStillLoads() {
        val page = page(1, listOf(
            row(0, "user", "ship the reader fix"),
            settlement(10, "withheld", "a future operation this build has never heard of"),
            row(20, "assistant", "done, it is live"),
            settlement(30, "blocked", "Chat blocked this message, nothing was sent"),
            row(40, "user", "thanks"),
            row(50, "assistant", "any time"),
        ))

        assertEquals(listOf(0L, 20L, 30L, 40L, 50L), page.rows.map { it.offset })
        assertEquals(ChatHistoryProtocol.QueueOperation.BLOCKED, page.rows[2].settlement?.operation)
        val history = ChatHistory(sid = sid).apply(page)
        assertEquals(listOf("ship the reader fix", "done, it is live", "thanks", "any time"), history.messages.map { it.text })
        assertEquals(60L, history.offset)
    }

    @Test
    fun aFutureSettlementRowParsesToNothingInsteadOfThrowing() {
        assertNull(ChatHistoryProtocol.parseRow(settlement(10, "withheld", "future")))
        val alone = page(2, listOf(settlement(10, "withheld", "future")))
        assertTrue(alone.rows.isEmpty())
        assertTrue(ChatHistory(sid = sid).apply(alone).messages.isEmpty())
    }
}
