package dev.multiprompt.companion.reader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageSnapshotHostSettingsTest {
    private val scope = MessageProtocol.Scope("claude", "6f353a80-d3df-4e44-88aa-edcc6c2b8999", "poteto-2-lc")

    private fun reply(items: JSONArray) = JSONObject().put("id", 3).put("message_protocol", 1).put("result",
        JSONObject().put("snapshot_id", "abc").put("epoch", 0).put("seq", 18015).put("items", items).put("next_cursor", JSONObject.NULL)).toString()

    private fun message() = JSONObject().put("message", JSONObject().put("id", "m".repeat(20)).put("provider", "claude").put("sid", scope.sid)
        .put("text", "hello").put("position", 1).put("attempt", "a1").put("state", "accepted").put("deadline", JSONObject.NULL)
        .put("receipt", JSONObject.NULL).put("epoch", 0).put("created", 1.0))

    @Test fun aDesktopHostedChatSnapshotWithHostSettingsStillLoads() {
        val settings = JSONObject().put("host_settings", JSONObject().put("model", "").put("effort", "").put("applied", false).put("mode", "bypassPermissions"))
        val page = MessageProtocol.snapshot(reply(JSONArray().put(message()).put(settings)), 3, scope)
        assertEquals(1, page.items.size)
        assertEquals("hello", (page.items.single() as MessageProtocol.Item.Message).row.text)
    }

    @Test fun anUnknownSnapshotItemIsStillRefused() {
        try {
            MessageProtocol.snapshot(reply(JSONArray().put(JSONObject().put("surprise", 1))), 3, scope)
            throw AssertionError("unknown item accepted")
        } catch (_: IllegalArgumentException) { }
    }
}
