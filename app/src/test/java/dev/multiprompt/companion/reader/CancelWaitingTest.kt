package dev.multiprompt.companion.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CancelWaitingTest {
    private val sid = "cancel-waiting-proof"
    private val created = 1_000.0

    private fun row(id: String, state: MessageProtocol.State, text: String = "hello") =
        MessageProtocol.Row(id, "claude", sid, text, 1, "attempt", state, null, null, 1, created)

    private fun drawn(vararg rows: MessageProtocol.Row, now: Double? = created + 5, seen: Map<String, Double> = emptyMap()) =
        ChatHistory(sid = sid, inode = 1, offset = 100).managedMessages(
            MessageReplica(1, 1, rows.associateBy { it.id }),
            emptyList(), now, false, seen,
        )

    @Test fun aQueuedMessageCanBeCancelled() {
        assertTrue(drawn(row("queuedmessage0000001", MessageProtocol.State.QUEUED)).single().cancellable)
    }

    @Test fun aReceivedMessageNotYetReadCanBeCancelled() {
        val card = drawn(row("sentmessage000000001", MessageProtocol.State.SENT)).single()
        assertTrue(card.cancellable)
        assertTrue(card.pending)
    }

    @Test fun aMessageTheBotAlreadyReadCannotBeCancelled() {
        for (state in listOf(MessageProtocol.State.ACCEPTED, MessageProtocol.State.STARTED, MessageProtocol.State.CANCELLED)) {
            assertFalse(state.name, drawn(row("readmessage00000001", state)).single().cancellable)
        }
    }

    @Test fun aFailedMessageKeepsRemoveAndSendAgainInsteadOfCancel() {
        val card = drawn(row("failedmessage00000001", MessageProtocol.State.FAILED)).single()
        assertFalse(card.cancellable)
        assertEquals(ChatHistory.NOT_SENT, card.delivery)
    }

    @Test fun aWaitingMessageStopsBeingCancellableWhenItTurnsNotSent() {
        val id = "sentmessage000000002"
        assertTrue(drawn(row(id, MessageProtocol.State.SENT), now = created + 599).single().cancellable)
        val late = drawn(row(id, MessageProtocol.State.SENT), now = created + 600).single()
        assertFalse(late.cancellable)
        assertEquals(ChatHistory.NOT_SENT, late.delivery)
    }

    @Test fun cancellingOneWaitingMessageLeavesTheOthersAlone() {
        val first = row("sentmessage000000003", MessageProtocol.State.SENT, "first")
        val second = row("sentmessage000000004", MessageProtocol.State.SENT, "second")
        val cards = drawn(first, second)
        assertEquals(setOf(first.id, second.id), cards.filter { it.cancellable }.map { it.requestId }.toSet())
        val afterCancel = drawn(first, second.copy(state = MessageProtocol.State.CANCELLED))
        assertEquals(listOf(first.id), afterCancel.filter { it.cancellable }.map { it.requestId })
    }

    @Test fun cancellableMatchesTheRowStateOnly() {
        assertTrue(ChatHistory.cancellable(row("sentmessage000000005", MessageProtocol.State.SENT), created + 1))
        assertTrue(ChatHistory.cancellable(row("sentmessage000000005", MessageProtocol.State.SENT), null))
        assertFalse(ChatHistory.cancellable(row("sentmessage000000005", MessageProtocol.State.ACCEPTED), created + 1))
    }
}
