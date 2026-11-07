package dev.multiprompt.companion.ui

import dev.multiprompt.companion.reader.ChatHistoryMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AwaitingReplyTest {
    private fun message(role: String, key: String) = ChatHistoryMessage(key, role, "text", "", 0, 0)

    @Test
    fun hisMessageWithNoReplyBelowShowsDots() {
        assertTrue(awaitingReply(listOf(message("assistant", "a"), message("user", "u")), 0))
    }

    @Test
    fun replyBelowHisMessageStopsDots() {
        assertFalse(awaitingReply(listOf(message("user", "u"), message("assistant", "a")), 0))
    }

    @Test
    fun openQuestionTakesThePlaceOfDots() {
        assertFalse(awaitingReply(listOf(message("user", "u")), 1))
    }

    @Test
    fun emptyChatShowsNoDots() {
        assertFalse(awaitingReply(emptyList(), 0))
    }
}
