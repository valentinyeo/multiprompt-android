package dev.multiprompt.companion.ui

import dev.multiprompt.companion.reader.MessageProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class LiveChatEventParseTest {
    private val sid = "d5339a17-2bfb-4710-a297-4c7548e0e636"
    private val scope = MessageProtocol.Scope("claude", sid, "live-1d21840")

    private fun host(kind: String, item: String?, payload: String, turn: String? = "t-1") = JSONObject()
        .put("v", 1).put("sid", sid).put("turn", turn ?: JSONObject.NULL).put("kind", kind)
        .put("at", 1793653981461L).put("item", item ?: JSONObject.NULL).put("payload", JSONObject(payload)).toString()

    private fun row(state: String, text: String, position: Int) = JSONObject()
        .put("id", "mp-abcdef0123456789").put("provider", "claude").put("sid", sid).put("text", text)
        .put("position", position).put("attempt", "attempt0123456789").put("state", state)
        .put("deadline", JSONObject.NULL).put("receipt", JSONObject.NULL).put("epoch", 0)
        .put("created", 1793653981.5).toString()

    @Test
    fun hostEventsFromTheDesktopHostParse() {
        val bodies = listOf(
            host("host.status", null, """{"state":"ready"}"""),
            row("accepted", "say PONG", 1),
            host("turn.started", null, """{"message_id":"mp-abcdef0123456789","text_sha256":"a"}"""),
            host("text.delta", "msg_01:text:0", """{"text":"PO","done":false}"""),
            host("text.done", "msg_01:text:0", """{"text":"PONG"}"""),
            host("turn.ended", null, """{"status":"completed","stop_reason":"end_turn"}"""),
        )
        val reply = JSONObject().put("id", 1).put("message_protocol", 1)
            .put("result", JSONObject().put("epoch", 0).put("seq", bodies.size).put("next_after", bodies.size)
                .put("events", org.json.JSONArray(bodies.mapIndexed { index, body ->
                    JSONObject().put("seq", index + 1).put("event", JSONObject(body))
                }))).toString()

        val parsed = runCatching { MessageProtocol.events(reply, 1, scope, 0) }
            .getOrElse { fail("a live chat's host events must parse: ${it.message}"); return }

        assertEquals(6L, parsed.next)
        assertEquals(6, parsed.events.size)
    }
}
