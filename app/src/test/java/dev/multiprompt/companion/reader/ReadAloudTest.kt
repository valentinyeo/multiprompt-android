package dev.multiprompt.companion.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadAloudTest {
    private fun message(role: String, text: String, pending: Boolean = false) =
        ChatHistoryMessage(key = "$role-$text", role = role, text = text, timestamp = "", offset = 0, end = 0, pending = pending)

    @Test fun newestAssistantReplyComesFirstAndUserTextStaysSilent() {
        val replies = ReadAloud.replies(
            listOf(
                message("user", "first question"),
                message("assistant", "first answer"),
                message("user", "second question"),
                message("assistant", "second answer"),
            ),
        )
        assertEquals(listOf("second answer", "first answer"), replies.map { it.text })
    }

    @Test fun emptyToolOnlyAndPendingRepliesStaySilent() {
        val replies = ReadAloud.replies(
            listOf(
                message("assistant", "   "),
                message("assistant", "```\nls -la\n```"),
                message("assistant", "still arriving", pending = true),
                message("assistant", "real answer"),
            ),
        )
        assertEquals(listOf("real answer"), replies.map { it.text })
    }

    @Test fun eachPressReadsTheNextOlderReplyThenWraps() {
        val replies = listOf(ReadAloud.Spoken("a", "newest"), ReadAloud.Spoken("b", "older"))
        assertEquals(ReadAloud.Spoken("a", "newest") to 1, ReadAloud.next(replies, 0))
        assertEquals(ReadAloud.Spoken("b", "older") to 2, ReadAloud.next(replies, 1))
        assertEquals(ReadAloud.Spoken("a", "newest") to 1, ReadAloud.next(replies, 2))
        assertEquals(null, ReadAloud.next(emptyList(), 0))
    }
}
