package dev.multiprompt.companion.ui

import dev.multiprompt.companion.reader.ChatHistoryMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class NewBelowTest {
    private fun message(role: String, key: String, pending: Boolean = false) =
        ChatHistoryMessage(key, role, "text", "", 0, 0, pending = pending)

    private val loaded = listOf(message("user", "u1"), message("assistant", "a1"))

    @Test
    fun twoNewRepliesCountTwo() {
        val after = loaded + message("assistant", "a2") + message("assistant", "a3")
        assertEquals(2, newRepliesBetween(loaded, after))
    }

    @Test
    fun nothingNewCountsZero() {
        assertEquals(0, newRepliesBetween(loaded, loaded.toList()))
    }

    @Test
    fun firstLoadCountsZero() {
        assertEquals(0, newRepliesBetween(emptyList(), loaded))
    }

    @Test
    fun olderPageAtTheTopCountsZero() {
        val after = listOf(message("user", "u0"), message("assistant", "a0")) + loaded
        assertEquals(0, newRepliesBetween(loaded, after))
    }

    @Test
    fun yourOwnMessageCountsZero() {
        assertEquals(0, newRepliesBetween(loaded, loaded + message("user", "u2")))
    }

    @Test
    fun replyStillStreamingCountsOnceWhenItFinishes() {
        val streaming = loaded + message("assistant", "@live:1:x", pending = true)
        assertEquals(0, newRepliesBetween(loaded, streaming))
        val finished = loaded + message("assistant", "@live:1:x")
        assertEquals(1, newRepliesBetween(streaming, finished))
    }

    @Test
    fun liveReplyReplacedByTranscriptRowIsNotCountedTwice() {
        val live = loaded + message("assistant", "@live:1:x")
        val landed = loaded + message("assistant", "s:1:assistant:native:x")
        assertEquals(0, newRepliesBetween(live, landed))
    }

    @Test
    fun largeCountsAreCapped() {
        assertEquals("2", jumpCountLabel(2))
        assertEquals("99", jumpCountLabel(99))
        assertEquals("99+", jumpCountLabel(100))
    }
}
