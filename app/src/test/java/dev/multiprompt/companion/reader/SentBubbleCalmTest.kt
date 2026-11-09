package dev.multiprompt.companion.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentBubbleCalmTest {
    private val sid = "calm-proof"
    private val id = "sharedmessage123456"

    private fun history() = ChatHistory(sid = sid, inode = 1, offset = 100)

    private fun drawn(state: MessageProtocol.State, unresolved: List<MessageJournal.Entry> = emptyList()) =
        history().managedMessages(
            MessageReplica(1, 1, mapOf(id to MessageProtocol.Row(id, "claude", sid, "calm text", 1, "attempt", state, null, null, 1, 1.0))),
            unresolved,
        )

    @Test fun aQueuedHostCopyCarriesNoLabel() {
        val sent = drawn(MessageProtocol.State.QUEUED).single()
        assertEquals("calm text", sent.text)
        assertTrue(sent.pending)
        assertNull(sent.delivery)
    }

    @Test fun anAcceptedHostCopyCarriesNoLabel() {
        assertNull(drawn(MessageProtocol.State.ACCEPTED).single().delivery)
    }

    @Test fun aFailedHostCopySaysNotSent() {
        val failed = drawn(MessageProtocol.State.FAILED).single()
        assertEquals("Not sent", failed.delivery)
        assertTrue(!failed.pending)
    }

    @Test fun anUnknownSubmitCarriesNoLabel() {
        val entry = MessageJournal.Entry("unknown123456789012", "account", MessageProtocol.Scope("claude", sid, "calm"),
            "submit", "othermessage123456", "first try", 1, "attempt", true)
        val copies = drawn(MessageProtocol.State.QUEUED, listOf(entry))
        assertEquals(listOf("calm text", "first try"), copies.map { it.text })
        assertTrue(copies.all { it.delivery == null })
    }

    @Test fun aPlainChatStillLabelsItsUnconfirmedEcho() {
        val prompt = QueuedMessages.SentPrompt("plain text", 1, sid = sid, inode = 1, afterOffset = 100)
        assertEquals("Local · unconfirmed", history().displayMessages(listOf(prompt)).single().delivery)
    }
}
