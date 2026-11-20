package dev.multiprompt.companion.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotSentReasonTest {
    private val sid = "reason-proof"
    private val id = "sharedmessage123456"

    @Test fun draftPresentReceiptYieldsBusyTypingDetailOnTheFailedMessage() {
        val row = MessageProtocol.Row(id, "claude", sid, "hello", 1, "attempt", MessageProtocol.State.FAILED, null, "host_draft_present", 1, 1.0)
        val message = ChatHistory(sid = sid).projectMessages(MessageReplica(1, 1, mapOf(id to row))).single()
        assertEquals(ChatHistory.NOT_SENT, message.delivery)
        assertEquals("Chat was busy typing, try again", message.notSentDetail)
    }

    @Test fun jsonConnectorReceiptKeepsTheReasonCode() {
        val receipt = """{"kind":"not_sent","sid":"$sid","reason":"session_changed","id":"$id","attempt":"a"}"""
        assertEquals("Chat closed", NotSentReason.plainText(receipt))
    }

    @Test fun bareNotSentReceiptUsesAGenericLine() {
        assertEquals("Could not send. Try again.", NotSentReason.plainText("not_sent"))
    }

    @Test fun aQueuedMessageCarriesNoDetail() {
        val row = MessageProtocol.Row(id, "claude", sid, "hello", 1, "attempt", MessageProtocol.State.QUEUED, null, null, 1, 1.0)
        assertNull(ChatHistory(sid = sid).projectMessages(MessageReplica(1, 1, mapOf(id to row))).single().notSentDetail)
    }
}