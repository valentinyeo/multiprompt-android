package dev.multiprompt.companion.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class LineBreakEchoTest {
    private val sid = "line-break-proof"
    private val id = "mp-linebreak0123456"
    private val spoken = "2026-11-04T00:00:10Z"
    private val sentAt = java.time.Instant.parse("2026-11-04T00:00:09Z").epochSecond.toDouble()

    private fun history(vararg rows: ChatHistoryProtocol.Row) = ChatHistory(sid = sid, inode = 1, offset = 100, rows = rows.toList())

    private fun replica(text: String) = MessageReplica(1, 1, mapOf(id to MessageProtocol.Row(id, "claude", sid, text, 1, "attempt0123456789",
        MessageProtocol.State.ACCEPTED, null, null, 1, sentAt)))

    @Test fun aSentMessageWithWindowsLineBreaksShowsOnce() {
        val drawn = history(ChatHistoryProtocol.Row("user", "line one\r\nline two", "u1", spoken, 100, 180))
            .managedMessages(replica("line one\nline two"), emptyList())
        assertEquals(1, drawn.count { it.role == "user" })
        assertEquals("@shared:$id", drawn.single { it.role == "user" }.key)
    }

    @Test fun aSentMessageMatchesWhenOnlyTheSentCopyHasWindowsLineBreaks() {
        val drawn = history(ChatHistoryProtocol.Row("user", "line one\nline two", "u1", spoken, 100, 180))
            .managedMessages(replica("line one\r\nline two"), emptyList())
        assertEquals(1, drawn.count { it.role == "user" })
    }

    @Test fun differentTextStillShowsBothMessages() {
        val drawn = history(ChatHistoryProtocol.Row("user", "line one\nline three", "u1", spoken, 100, 180))
            .managedMessages(replica("line one\nline two"), emptyList())
        assertEquals(2, drawn.count { it.role == "user" })
    }
}
