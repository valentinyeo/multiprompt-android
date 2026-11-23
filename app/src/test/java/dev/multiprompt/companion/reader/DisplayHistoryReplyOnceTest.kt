package dev.multiprompt.companion.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DisplayHistoryReplyOnceTest {
    private val sid = "display-history-reply-once"
    private val divider = "---\nFresh start\n---"
    private val userId = "mp-fresh00000000002"
    private val replyText = "REPLY_AFTER_FRESH_START"
    private val sentAt = java.time.Instant.parse("2026-11-13T10:00:00Z").epochSecond.toDouble()

    private fun userRow() = ChatHistoryProtocol.Row("user", "after restart", userId, "2026-11-13T10:06:10Z", 300, 340)

    private fun dividerRow() =
        ChatHistoryProtocol.Row("assistant", divider, "history-divider", "2026-11-13T10:05:00Z", 200, 240)

    private fun card() = MessageProtocol.Row(
        userId, "claude", sid, "after restart", 2, "attempt0123456789",
        MessageProtocol.State.ACCEPTED, null, null, 1, sentAt + 360,
    )

    private fun replica(turn: String, item: String, text: String, done: Boolean) =
        MessageReplica(
            1, 1,
            mapOf(userId to card()),
            host = HostStream(
                turn = turn,
                blocks = listOf(HostBlock(item, "text", text, done, messageId = userId)),
            ),
        )

    private fun once(messages: List<ChatHistoryMessage>, expected: List<String>) {
        assertEquals(expected, messages.map { it.text })
        val counts = messages.groupingBy { it.text }.eachCount()
        assertFalse(
            "MESSAGE_PROJECTION_DUPLICATE: reply showed more than once. $counts",
            counts.any { it.value > 1 },
        )
    }

    @Test fun legacyHistoryUuidSavedReplyAndLiveCopyShowOnce() {
        val historyId = "history-af58a66a27964724a0178276ac82d175"
        val liveItem = "msg_after_fresh:text:0"
        val rows = listOf(
            dividerRow(),
            userRow(),
            ChatHistoryProtocol.Row("assistant", replyText, historyId, "2026-11-13T10:06:20Z", 400, 440),
        )
        val projected = ChatHistory(sid = sid, inode = 7, rows = rows)
            .projectMessages(replica("t-after", liveItem, replyText, done = false))
        once(projected, listOf(divider, "after restart", replyText))
    }

    @Test fun liveItemSavedReplyAndLiveCopyShowOnce() {
        val liveItem = "msg_after_fresh:text:0"
        val rows = listOf(
            dividerRow(),
            userRow(),
            ChatHistoryProtocol.Row("assistant", replyText, liveItem, "2026-11-13T10:06:20Z", 400, 440),
        )
        var host = HostStream(turn = "t-after", messageId = userId)
        host = host.apply(MessageProtocol.HostEvent("text.delta", "t-after", "text.delta", 0L, replyText, false, null))
        val projected = ChatHistory(sid = sid, inode = 7, rows = rows)
            .projectMessages(MessageReplica(1, 1, mapOf(userId to card()), host = host))
        once(projected, listOf(divider, "after restart", replyText))
    }

}