package dev.multiprompt.companion.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotSentTest {
    private val sid = "not-sent-proof"
    private val id = "sharedmessage654321"
    private val created = 1_000.0

    private fun drawn(state: MessageProtocol.State, now: Double?, unresolved: List<MessageJournal.Entry> = emptyList(), offline: Boolean = false, seen: Map<String, Double> = emptyMap()) =
        ChatHistory(sid = sid, inode = 1, offset = 100).managedMessages(
            MessageReplica(1, 1, mapOf(id to MessageProtocol.Row(id, "claude", sid, "hello", 1, "attempt", state, null, null, 1, created))),
            unresolved, now, offline, seen,
        )

    @Test fun aQueuedMessageWaitsTenMinutesBeforeNotSent() {
        val early = drawn(MessageProtocol.State.QUEUED, created + 599).single()
        assertNull(early.delivery)
        assertTrue(early.pending)
        val late = drawn(MessageProtocol.State.QUEUED, created + 600).single()
        assertEquals("Not sent", late.delivery)
        assertFalse(late.pending)
        assertEquals(id, late.requestId)
    }

    @Test fun theWaitStartsWhenThePhoneFirstSeesTheMessage() {
        val seen = mapOf(id to created + 5_000)
        assertNull(drawn(MessageProtocol.State.QUEUED, created + 5_599, seen = seen).single().delivery)
        assertEquals("Not sent", drawn(MessageProtocol.State.QUEUED, created + 5_600, seen = seen).single().delivery)
    }

    @Test fun aSentMessageThatNeverLandsSaysNotSent() {
        assertEquals("Not sent", drawn(MessageProtocol.State.SENT, created + 601).single().delivery)
    }

    @Test fun anAcceptedMessageNeverSaysNotSent() {
        assertNull(drawn(MessageProtocol.State.ACCEPTED, created + 5_000).single().delivery)
    }

    @Test fun anUnknownSubmitSaysNotSentOnlyWhileOffline() {
        val entry = MessageJournal.Entry("unknown123456789012", "account", MessageProtocol.Scope("claude", sid, "calm"),
            "submit", "othermessage123456", "lost text", 1, "attempt", true)
        val online = drawn(MessageProtocol.State.ACCEPTED, created, listOf(entry)).single { it.text == "lost text" }
        assertNull(online.delivery)
        val offline = drawn(MessageProtocol.State.ACCEPTED, created, listOf(entry), offline = true).single { it.text == "lost text" }
        assertEquals("Not sent", offline.delivery)
        assertEquals("unknown123456789012", offline.unknownId)
    }
}
