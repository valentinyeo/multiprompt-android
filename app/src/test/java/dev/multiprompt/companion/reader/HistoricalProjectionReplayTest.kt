package dev.multiprompt.companion.reader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoricalProjectionReplayTest {
    private val sid = "historical-projection"
    private val created = java.time.Instant.parse("2026-11-13T10:00:00Z").epochSecond.toDouble()
    private val id = "mp-history000000001"
    private fun card(text: String) = MessageProtocol.Row(id, "claude", sid, text, 1, "attempt0123456789",
        MessageProtocol.State.ACCEPTED, null, null, 1, created)
    private fun history(vararg rows: ChatHistoryProtocol.Row) = ChatHistory(sid = sid, inode = 7, rows = rows.toList())
    private fun user(text: String, identity: String = "u1", stamp: String = "2026-11-13T10:00:01Z") =
        ChatHistoryProtocol.Row("user", text, identity, stamp, 100, 180)
    private fun event(kind: String, turn: String, item: String? = null, payload: JSONObject = JSONObject()) = JSONObject()
        .put("kind", kind).put("sid", sid).put("turn", turn).put("payload", payload).also { if (item != null) it.put("item", item) }
    private fun replica(card: MessageProtocol.Row, events: List<JSONObject> = emptyList()): MessageReplica {
        val packet = JSONObject().put("id", 1).put("message_protocol", 1).put("result", JSONObject()
            .put("snapshot_id", "replay").put("epoch", 1).put("seq", 1).put("next_cursor", JSONObject.NULL)
            .put("items", JSONArray(events)))
        val parsed = MessageProtocol.snapshot(packet.toString(), 1, MessageProtocol.Scope("claude", sid, "verify-session"))
        return MessageReplica.snapshot(listOf(parsed.copy(items = listOf(MessageProtocol.Item.Message(card)) + parsed.items)))
    }

    @Test fun lineBreakEchoFromPr245() {
        val drawn = history(user("line one\r\nline two")).projectMessages(replica(card("line one\nline two")), emptyList())
        assertEquals("MESSAGE_PROJECTION_DUPLICATE", listOf("line one\r\nline two"), drawn.map { it.text })
    }

    @Test fun liveChatStreamFromPr286() {
        val events = listOf(event("turn.started", "t-1"),
            event("text.done", "t-1", "a:text:0", JSONObject().put("text", "Here is the answer.")),
            event("turn.ended", "t-1"), event("turn.started", "t-2"))
        val replica = replica(card("second").copy(position = 2, state = MessageProtocol.State.QUEUED), events)
        val drawn = history(user("first", stamp = "")).projectMessages(replica, emptyList())
        assertEquals("MESSAGE_PROJECTION_REPLY_LOST", listOf("first", "Here is the answer.", "second"), drawn.map { it.text })
    }

    @Test fun liveMessageOnceFromPr308() {
        val events = listOf(event("turn.started", "t-1", payload = JSONObject().put("message_id", id).put("uuids", JSONArray(listOf("u1")))),
            event("text.delta", "t-1", "msg_1:text:0", JSONObject().put("text", "answer").put("done", false)))
        val drawn = history(user("[Image #1] question"), ChatHistoryProtocol.Row("assistant", "answer 1", "msg_1", "", 180, 300))
            .projectMessages(replica(card("question"), events), emptyList())
        assertEquals("MESSAGE_PROJECTION_DUPLICATE", listOf("[Image #1] question", "answer 1"), drawn.map { it.text })
        assertEquals("MESSAGE_PROJECTION_DUPLICATE", listOf(id), drawn.filter { it.role == "user" }.map { it.requestId })
    }

    @Test fun freshStartChatFromPr312() {
        val divider = ChatHistoryProtocol.Row("assistant", "---\nFresh start\n---", "divider", "", 0, 100)
        val answer = ChatHistoryProtocol.Row("assistant", "new answer", "reply", "", 180, 300)
        val drawn = history(divider, user("after restart", id, "2026-11-13T10:00:01.123456"), answer)
            .projectMessages(replica(card("after restart")), emptyList())
        assertEquals("MESSAGE_PROJECTION_DUPLICATE", listOf(divider.text, "after restart", "new answer"), drawn.map { it.text })
    }
}
