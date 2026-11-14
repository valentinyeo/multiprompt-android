package dev.multiprompt.companion.ui

import dev.multiprompt.companion.reader.ChatHistoryMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatOpenAnchorTest {
    private fun message(role: String, key: String, timestamp: String = "", pending: Boolean = false) =
        ChatHistoryMessage(key, role, "text", timestamp, 0, 0, pending = pending)

    private val read = 1_700_000_000L

    private fun at(offsetSeconds: Long): String = java.time.Instant.ofEpochSecond(read + offsetSeconds).toString()

    @Test
    fun markedKeyWinsOverReadTime() {
        val messages = listOf(message("assistant", "a", at(5)), message("assistant", "b", at(9)))
        assertEquals("b", ChatOpenAnchor.unreadKey(messages, "b", read))
    }

    @Test
    fun firstReplyAfterReadTimeIsTheNewLine() {
        val messages = listOf(
            message("assistant", "old", at(-30)),
            message("user", "u", at(-10)),
            message("assistant", "n1", at(10)),
            message("assistant", "n2", at(20)),
        )
        assertEquals("n1", ChatOpenAnchor.unreadKey(messages, null, read))
    }

    @Test
    fun nothingNewMeansNoNewLine() {
        val messages = listOf(message("assistant", "old", at(-30)), message("user", "u", at(-10)))
        assertNull(ChatOpenAnchor.unreadKey(messages, null, read))
    }

    @Test
    fun noReadTimeMeansNoNewLine() {
        val messages = listOf(message("assistant", "a", at(10)))
        assertNull(ChatOpenAnchor.unreadKey(messages, null, null))
        assertNull(ChatOpenAnchor.unreadKey(messages, null, 0L))
    }

    @Test
    fun userMessagesAndPendingRepliesDoNotCount() {
        val messages = listOf(
            message("assistant", "old", at(-30)),
            message("user", "u", at(10)),
            message("assistant", "p", at(12), pending = true),
        )
        assertNull(ChatOpenAnchor.unreadKey(messages, null, read))
    }

    @Test
    fun unparseableTimestampIsSkipped() {
        val messages = listOf(message("assistant", "bad", "not a time"), message("assistant", "n", at(10)))
        assertEquals("n", ChatOpenAnchor.unreadKey(messages, null, read))
    }

    @Test
    fun listIndexCountsFromTheNewestEnd() {
        val messages = listOf(message("assistant", "a"), message("user", "b"), message("assistant", "c"))
        assertEquals(0, ChatOpenAnchor.listIndex(messages, "c", 0, false))
        assertEquals(2, ChatOpenAnchor.listIndex(messages, "a", 0, false))
    }

    @Test
    fun listIndexSkipsQuestionsAndWorkingDots() {
        val messages = listOf(message("assistant", "a"), message("assistant", "b"))
        assertEquals(3, ChatOpenAnchor.listIndex(messages, "a", 2, false))
        assertEquals(4, ChatOpenAnchor.listIndex(messages, "a", 2, true))
    }

    @Test
    fun listIndexIsNullWithoutTarget() {
        val messages = listOf(message("assistant", "a"))
        assertNull(ChatOpenAnchor.listIndex(messages, null, 0, false))
        assertNull(ChatOpenAnchor.listIndex(messages, "missing", 0, false))
    }

    @Test
    fun shortItemSitsAtTheTopOfTheViewport() {
        assertEquals(-700, ChatOpenAnchor.scrollOffset(300, 1000))
    }

    @Test
    fun tallItemShowsItsTopEdge() {
        assertEquals(500, ChatOpenAnchor.scrollOffset(1500, 1000))
    }
}
