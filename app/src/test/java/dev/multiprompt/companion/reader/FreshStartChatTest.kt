package dev.multiprompt.companion.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class FreshStartChatTest {
    private val sid = "fresh-start-proof"
    private val sent = java.time.Instant.parse("2026-11-13T10:00:00Z").epochSecond.toDouble()
    private val older = "mp-fresh00000000001"
    private val newer = "mp-fresh00000000002"
    private val divider = "---\nFresh start\n---"

    private fun card(id: String, position: Long, text: String) = MessageProtocol.Row(id, "claude", sid, text, position,
        "attempt0123456789", MessageProtocol.State.ACCEPTED, null, null, 1, sent + if (position == 1L) 20 else 360)

    private fun history(id: String, role: String, text: String, stamp: String, offset: Long) =
        ChatHistoryProtocol.Row(role, text, id, stamp, offset, offset + 40)

    private fun rows() = listOf(
        history("u-1", "user", "before restart", "2026-11-13T10:00:30Z", 0),
        history("msg_1", "assistant", "old answer", "2026-11-13T10:00:40Z", 100),
        history("history-0001", "assistant", divider, "2026-11-13T10:05:00.123456", 200),
        history(newer, "user", "after restart", "2026-11-13T10:06:10.654321", 300),
        history("history-0002", "assistant", "new answer", "2026-11-13T10:06:20.111111", 400),
    )

    @Test fun aPhoneMessageSentAfterFreshStartShowsOnce() {
        val replica = MessageReplica(1, 1, mapOf(older to card(older, 1, "before restart"), newer to card(newer, 2, "after restart")))

        val drawn = ChatHistory(sid = sid, inode = 7, offset = 0, rows = rows()).managedMessages(replica, emptyList())

        assertEquals(listOf("before restart", "old answer", divider, "after restart", "new answer"), drawn.map { it.text })
        assertEquals(listOf("@shared:$older", "@shared:$newer"), drawn.filter { it.role == "user" }.map { it.key })
    }

    @Test fun olderMessagesStayAboveTheDividerWithoutCards() {
        val drawn = ChatHistory(sid = sid, inode = 7, offset = 0, rows = rows()).managedMessages(null, emptyList())

        assertEquals(listOf("before restart", "old answer", divider, "after restart", "new answer"), drawn.map { it.text })
    }

    @Test fun aMessageStillWaitingForTheRestartedChatShowsOnce() {
        val queued = card(newer, 2, "after restart").copy(state = MessageProtocol.State.QUEUED)
        val replica = MessageReplica(1, 1, mapOf(older to card(older, 1, "before restart"), newer to queued))

        val drawn = ChatHistory(sid = sid, inode = 7, offset = 0, rows = rows().take(3)).managedMessages(replica, emptyList())

        assertEquals(listOf("before restart", "old answer", divider, "after restart"), drawn.map { it.text })
        assertEquals(true, drawn.last().pending)
    }
}
