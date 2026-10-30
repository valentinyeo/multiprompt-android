package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import org.junit.Assert.*
import org.junit.Test

class QueuedMessagesTest {
    private val first = QueuedMessages.SentPrompt("same", 1, "first", QueuedMessages.Delivery.QUEUED, "sid", 9, 100)
    private val second = first.copy(messageId = "second")
    private fun ChatHistory.display(sent: List<QueuedMessages.SentPrompt> = listOf(first, second)) =
        displayMessages("Press up to edit queued messages\n> same", AgentKind.CLAUDE, sent, Long.MAX_VALUE)

    @Test fun onlyIdFactsSettleAndIdenticalIntentionsStaySeparate() {
        val row = ChatHistoryProtocol.Row("user", "same", "row", "2099-01-01T00:00:00Z", 100, 110, queued = true)
        val reply = ChatHistoryProtocol.Row("assistant", "same", "reply", "", 111, 120)
        val removal = ChatHistoryProtocol.Row("assistant", "", "removal", "", 121, 122,
            settlement = ChatHistoryProtocol.Settlement(ChatHistoryProtocol.QueueOperation.REMOVE, "same"))
        val history = ChatHistory(sid = "sid", inode = 9, rows = listOf(row, reply, removal))
        assertFalse(history.messages.first().pending)
        val waiting = history.display()
        assertEquals(listOf("first", "second"), waiting.takeLast(2).map { it.requestId })
        assertEquals(listOf("Queued", "Queued"), waiting.takeLast(2).map { it.delivery })
        assertTrue(waiting.takeLast(2).all { it.pending })
        val settled = history.copy(deliveries = mapOf("first" to QueuedMessages.Delivery.DELIVERED,
            "second" to QueuedMessages.Delivery.CANCELLED)).display()
        assertEquals("Delivered", settled.first().delivery)
        assertEquals("Cancelled", settled.last().delivery)
        assertTrue(settled.none { it.pending })
    }

    @Test fun heldSnapshotOverridesStaleLocalAndDeliveryFacts() {
        val stale = first.copy(delivery = QueuedMessages.Delivery.CANCELLED)
        val history = ChatHistory(held = listOf(first), deliveries = mapOf("first" to QueuedMessages.Delivery.DELIVERED))
        val message = history.display(listOf(stale)).single()
        assertEquals("Queued", message.delivery)
        assertTrue(message.pending)
        assertEquals(listOf(stale), unconfirmedAfterResync(history, listOf(stale)))
    }

    @Test fun offPageDeliveryAndCancellationDoNotNeedTranscript() {
        val history = ChatHistory(deliveries = mapOf("first" to QueuedMessages.Delivery.DELIVERED,
            "second" to QueuedMessages.Delivery.CANCELLED))
        assertEquals(listOf("Delivered", "Cancelled"), history.display().map { it.delivery })
        assertTrue(unconfirmedAfterResync(history, listOf(first, second)).isEmpty())
    }

    @Test fun duplicateIdsCollapseButDuplicateTextsDoNot() {
        val result = ChatHistory(held = listOf(first)).display(listOf(first, first, second))
        assertEquals(listOf("first", "second"), result.map { it.requestId })
        assertEquals(2, result.map { it.key }.toSet().size)
    }

    @Test fun nativeEchoMatchingIsExactFreshBoundedAndOneToOne() {
        val native = QueuedMessages.SentPrompt("same", 1, sid = "sid", inode = 9, afterOffset = 100)
        val another = QueuedMessages.SentPrompt("same", 2, sid = "sid", inode = 9, afterOffset = 100)
        fun row(text: String, offset: Long) = ChatHistoryProtocol.Row("user", text, "id$offset", "", offset, offset + 1)
        val history = ChatHistory(sid = "sid", inode = 9, rows = listOf(row("same", 50), row("same extra", 101), row("same", 102)))
        val result = history.display(listOf(native, another))
        assertEquals(4, result.size)
        assertEquals(1, result.count { it.key.startsWith("@local:") })
        assertTrue(result.all { !it.pending && it.delivery == null })
        assertEquals(5, history.copy(sid = "other").display(listOf(native, another)).size)
        assertEquals(5, history.copy(inode = 10).display(listOf(native, another)).size)
        assertEquals(5, history.display(listOf(native.copy(sid = ""), another.copy(sid = ""))).size)
        assertEquals(listOf(native, another), unconfirmedAfterResync(history, listOf(native, another)))
    }
}
