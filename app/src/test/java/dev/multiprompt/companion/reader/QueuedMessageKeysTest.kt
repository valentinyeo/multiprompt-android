package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import org.junit.Assert.assertEquals
import org.junit.Test

class QueuedMessageKeysTest {
    @Test fun distinctWaitingMessagesKeepDistinctKeysWhenTextHashesCollide() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val messages = ChatHistory().displayMessages("", AgentKind.CLAUDE,
            listOf(QueuedMessages.SentPrompt("Aa", 100), QueuedMessages.SentPrompt("BB", 101)), 102)
        assertEquals(listOf("Aa", "BB"), messages.map { it.text })
        assertEquals(2, messages.map { it.key }.toSet().size)
    }
}
