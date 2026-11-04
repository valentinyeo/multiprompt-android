package dev.multiprompt.companion.reader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostRequestTest {
    private val scope = MessageProtocol.Scope("claude", "sid-1", "chat-1")

    private fun event(kind: String, turn: String, payload: JSONObject = JSONObject(), item: String? = null) =
        JSONObject().put("v", 1).put("sid", "sid-1").put("turn", turn).put("kind", kind).put("at", 1)
            .put("item", item ?: JSONObject.NULL).put("payload", payload)

    private fun page(after: Long, head: Long, vararg events: Pair<Long, JSONObject>): MessageProtocol.Events {
        val rows = JSONArray()
        events.forEach { (seq, value) -> rows.put(JSONObject().put("seq", seq).put("event", value)) }
        val next = events.lastOrNull()?.first ?: after
        val reply = JSONObject().put("id", 7).put("message_protocol", 1)
            .put("result", JSONObject().put("epoch", 1).put("seq", head).put("events", rows).put("next_after", next)).toString()
        return MessageProtocol.events(reply, 7, scope, after)
    }

    private val colours = JSONObject().put("request_id", "req_2").put("questions", JSONArray()
        .put(JSONObject().put("id", "0").put("header", "Accent").put("question", "Which colour?").put("multiSelect", false)
            .put("options", JSONArray().put(JSONObject().put("label", "Lime").put("description", "bright")).put(JSONObject().put("label", "Orange"))))
        .put(JSONObject().put("id", "1").put("header", "Devices").put("question", "Which devices?").put("multiSelect", true)
            .put("options", JSONArray().put(JSONObject().put("label", "Desktop")).put(JSONObject().put("label", "Phone")))))

    @Test fun aQuestionStaysOpenAcrossANewTurnUntilItsOwnResolvedEvent() {
        var replica = MessageReplica(1, 0)
        replica = replica.apply(page(0, 3,
            1L to event("turn.started", "t1"),
            2L to event("question.request", "t1", colours),
            3L to event("turn.started", "t2")))
        val open = requireNotNull(replica.host.request)
        assertEquals("question", open.kind)
        assertEquals("req_2", open.id)
        replica = replica.apply(page(3, 5,
            4L to event("text.delta", "t3", JSONObject().put("text", "thinking aloud"), "m1"),
            5L to event("question.resolved", "t3", JSONObject().put("request_id", "req_other"))))
        assertEquals("req_2", replica.host.request?.id)
        val cards = replica.host.request!!.cards()
        assertEquals(listOf("Which colour?", "Which devices?"), cards.map { it.title })
        assertEquals(listOf("Accent", "Devices"), cards.map { it.header })
        assertEquals(listOf("Lime", "Orange"), cards[0].options.map { it.label })
        assertEquals("bright", cards[0].options[0].description)
        assertEquals(listOf(false, true), cards.map { it.multiSelect })
        assertEquals(listOf("question", "question"), cards.map { it.host })
        replica = replica.apply(page(5, 6, 6L to event("question.resolved", "t3", JSONObject().put("request_id", "req_2"))))
        assertNull(replica.host.request)
    }

    @Test fun aRequestOpenedBeforeConnectComesBackFromTheReplayedEvents() {
        val replica = MessageReplica(1, 4)
        val replayed = replica.replayHost(page(0, 6,
            1L to event("turn.started", "t1"),
            2L to event("approval.request", "t1", JSONObject().put("request_id", "req_1").put("tool", "Bash").put("title", "Run rm -rf build")),
            4L to event("text.delta", "t1", JSONObject().put("text", "waiting"), "m1"),
            6L to event("approval.resolved", "t1", JSONObject().put("request_id", "req_1").put("decision", "accept"))).events)
        assertEquals(4, replayed.cursor)
        val open = requireNotNull(replayed.host.request)
        assertEquals("approval", open.kind)
        val card = open.cards().single()
        assertEquals("Run rm -rf build", card.title)
        assertEquals(listOf("Approve", "Always allow this session", "Decline"), card.options.map { it.label })
        assertEquals(false, card.other)
        assertEquals(listOf("waiting"), replayed.host.spoken.map { it.text })
    }

    @Test fun aPlanOffersImplementAndFeedback() {
        val replica = MessageReplica(1, 0).apply(page(0, 1,
            1L to event("plan.proposed", "t1", JSONObject().put("request_id", "req_3").put("markdown", "1. add a queue"))))
        val card = replica.host.request!!.cards().single()
        assertEquals("plan", card.host)
        assertEquals("1. add a queue", card.title)
        assertEquals(listOf("Implement"), card.options.map { it.label })
        assertEquals(true, card.other)
    }

    @Test fun replyLinesMatchTheDesktopBytes() {
        assertEquals("{\"id\":89,\"op\":\"approval_reply\",\"provider\":\"claude\",\"sid\":\"sid-1\",\"request_id\":\"req_1\",\"decision\":\"accept\"}",
            HostReply.approval(89, "sid-1", "req_1", "accept"))
        assertEquals("{\"id\":90,\"op\":\"question_reply\",\"provider\":\"claude\",\"sid\":\"sid-1\",\"request_id\":\"req_2\",\"answers\":{\"Which colour?\":\"Lime\",\"Which devices?\":\"Desktop, Phone\"},\"dismissed\":false}",
            HostReply.question(90, "sid-1", "req_2", linkedMapOf("Which colour?" to "Lime", "Which devices?" to "Desktop, Phone"), false))
        assertEquals("{\"id\":90,\"op\":\"question_reply\",\"provider\":\"claude\",\"sid\":\"sid-1\",\"request_id\":\"req_2\",\"answers\":{},\"dismissed\":true}",
            HostReply.question(90, "sid-1", "req_2", emptyMap(), true))
        assertEquals("{\"id\":91,\"op\":\"plan_reply\",\"provider\":\"claude\",\"sid\":\"sid-1\",\"request_id\":\"req_3\",\"action\":\"implement\"}",
            HostReply.plan(91, "sid-1", "req_3", null))
        assertEquals("{\"id\":91,\"op\":\"plan_reply\",\"provider\":\"claude\",\"sid\":\"sid-1\",\"request_id\":\"req_3\",\"action\":\"feedback\",\"feedback\":\"use a queue instead\"}",
            HostReply.plan(91, "sid-1", "req_3", "use a queue instead"))
    }

    @Test fun cardChoicesBecomeTheHostAnswer() {
        val replica = MessageReplica(1, 0).apply(page(0, 1, 1L to event("question.request", "t1", colours)))
        val (colour, devices) = replica.host.request!!.cards()
        assertEquals("Lime", HostReply.answer(colour, setOf(0), ""))
        assertEquals("Desktop, Phone", HostReply.answer(devices, setOf(1, 0), ""))
        assertEquals("Teal", HostReply.answer(colour, emptySet(), " Teal "))
        assertEquals("Desktop, Tablet", HostReply.answer(devices, setOf(0), "Tablet"))
        assertEquals(listOf("accept", "acceptForSession", "decline"), (0..2).map { HostReply.decision(it) })
    }

    @Test fun theHostStatusDecidesWhetherTheCardCloses() {
        fun reply(status: String, reason: String? = null) = JSONObject().put("id", 12).put("message_protocol", 1)
            .put("result", JSONObject().put("status", status).apply { if (reason != null) put("reason", reason) }).toString()
        assertEquals(HostReply.Outcome.Sent, HostReply.outcome(reply("ok"), 12))
        assertEquals(HostReply.Outcome.Gone, HostReply.outcome(reply("refused", "unknown_request"), 12))
        assertEquals(HostReply.Outcome.Failed("The agent host is not running"), HostReply.outcome(reply("unavailable", "host_not_running"), 12))
        assertEquals(HostReply.Outcome.Failed("The agent refused this answer"), HostReply.outcome(reply("refused", "invalid_decision"), 12))
    }
}
