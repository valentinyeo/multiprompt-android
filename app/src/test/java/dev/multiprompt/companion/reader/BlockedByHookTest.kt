package dev.multiprompt.companion.reader

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedByHookTest {
    private val sid = "blocked-proof"

    private val fixture: List<JSONObject> = sequenceOf(
        File("src/test/resources/reader/MUBO-246/blocked-by-hook.jsonl"),
        File("app/src/test/resources/reader/MUBO-246/blocked-by-hook.jsonl"),
    ).first { it.isFile }.readLines().filter { it.isNotBlank() }.map(::JSONObject)

    private val blocked = fixture.first { it.getJSONObject("promptSubmitEnding").optBoolean("promptKept") == false }
    private val prompt = blocked.getString("content").substringAfterLast("Original prompt:").trim()
    private val reason = blocked.getJSONObject("promptSubmitEnding").getJSONObject("ending").getString("kind")

    @Test fun blockedSettlementFromTheFixtureParsesAndAddsNoBubble() {
        val row = ChatHistoryProtocol.parseRow(JSONObject()
            .put("role", "assistant").put("text", "").put("id", "").put("timestamp", blocked.getString("timestamp"))
            .put("offset", 0).put("end", 1)
            .put("settlement", JSONObject().put("operation", "blocked").put("content", prompt)))
        assertEquals(ChatHistoryProtocol.QueueOperation.BLOCKED, row.settlement?.operation)
        assertEquals(prompt, row.settlement?.content)
        assertTrue(ChatHistory(rows = listOf(row)).messages.isEmpty())
    }

    @Test fun blockedSendFromTheFixtureShowsNotSentAtOnce() {
        val id = "blockedmessage123456"
        val receipt = JSONObject().put("kind", "not_sent").put("sid", sid).put("reason", reason)
            .put("id", id).put("attempt", "a1").toString()
        val row = MessageProtocol.Row(id, "claude", sid, prompt, 1, "a1", MessageProtocol.State.FAILED, null, receipt, 1, 1.0)
        val message = ChatHistory(sid = sid).projectMessages(MessageReplica(1, 1, mapOf(id to row)), now = 1.0).single()
        assertEquals(ChatHistory.NOT_SENT, message.delivery)
        assertEquals("Chat blocked this message, nothing was sent", message.notSentDetail)
    }

    @Test fun deliveredPromptFromTheFixtureIsNotBlamedForABlock() {
        val id = "deliveredmessage1234"
        val row = MessageProtocol.Row(id, "claude", sid, prompt, 1, "a1", MessageProtocol.State.SENT, null, null, 1, 1.0)
        val message = ChatHistory(sid = sid).projectMessages(MessageReplica(1, 1, mapOf(id to row)), now = 1.0).single()
        assertNull(message.delivery)
        assertTrue(message.pending)
    }
}
