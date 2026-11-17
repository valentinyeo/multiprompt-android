package dev.multiprompt.companion.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationLoadTest {
    @Test fun cachedHistoryDoesNotProveTheChatLinkIsUp() {
        val cached = ChatHistoryState(ChatHistory(sid = "poteto"), loaded = true)
        assertFalse(cached.ready)
        val live = cached.copy(connected = true)
        assertTrue(live.ready)
        assertFalse(live.copy(retrying = true).ready)
        assertFalse(live.copy(connected = false).ready)
        assertFalse(live.copy(messageError = "Shared messaging is unavailable on this host").ready)
        assertFalse(live.copy(history = ChatHistory()).ready)
    }

    @Test fun initialLoadHasADeadlineEvenWhenIndividualRepliesKeepArriving() {
        val second = 1_000_000_000L
        assertFalse(ConversationLoad.expired(9 * second, 0, 9 * second, false, 10_000))
        assertTrue(ConversationLoad.expired(10 * second, 0, 10 * second, false, 10_000))
        assertFalse(ConversationLoad.expired(10 * second, 0, 10 * second, true, 10_000))
    }

    @Test fun aPreviouslyLiveLinkTimesOutAfterTheLastReplyAndCanRecover() {
        val second = 1_000_000_000L
        assertFalse(ConversationLoad.expired(19 * second, 0, 10 * second, true, 10_000))
        assertTrue(ConversationLoad.expired(20 * second, 0, 10 * second, true, 10_000))
        assertFalse(ConversationLoad.expired(21 * second, 20 * second, 21 * second, true, 10_000))
    }
}
