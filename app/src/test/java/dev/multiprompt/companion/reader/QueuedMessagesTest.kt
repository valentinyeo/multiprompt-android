package dev.multiprompt.companion.reader

import org.junit.Assert.*
import org.junit.Test

class QueuedMessagesTest {
    private val first = QueuedMessages.SentPrompt("same", 1, "first", QueuedMessages.Delivery.QUEUED, "sid", 9, 100)
    private val second = first.copy(messageId = "second")
    private fun ChatHistory.display(sent: List<QueuedMessages.SentPrompt> = listOf(first, second)) =
        displayMessages(sent)

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

    @Test fun terminalFactsOverrideOlderHeldSnapshots() {
        for (terminal in listOf(QueuedMessages.Delivery.DELIVERED, QueuedMessages.Delivery.CANCELLED)) {
            val history = ChatHistory(sid = "sid", inode = 9, held = listOf(first))
            val localReceipt = first.copy(delivery = terminal)
            val fromLocal = history.display(listOf(localReceipt)).single()
            assertEquals(terminal.name.lowercase().replaceFirstChar { it.uppercase() }, fromLocal.delivery)
            assertFalse(fromLocal.pending)
            assertTrue(unconfirmedAfterResync(history, listOf(localReceipt)).isEmpty())
            val fromMap = history.copy(deliveries = mapOf("first" to terminal))
            assertEquals(fromLocal.delivery, fromMap.display(listOf(first)).single().delivery)
            assertTrue(unconfirmedAfterResync(fromMap, listOf(first)).isEmpty())
        }
        val submitted = first.copy(delivery = QueuedMessages.Delivery.SUBMITTED)
        val history = ChatHistory(sid = "sid", inode = 9, held = listOf(first))
        assertEquals("Queued", history.display(listOf(submitted)).single().delivery)
        assertEquals(listOf(submitted), unconfirmedAfterResync(history, listOf(submitted)))
    }

    @Test fun staleHistoryPageCannotOverwriteTerminalDelivery() {
        for (terminal in listOf(QueuedMessages.Delivery.DELIVERED, QueuedMessages.Delivery.CANCELLED)) {
            val history = ChatHistory(sid = "sid", inode = 9, deliveries = mapOf("first" to terminal))
            val page = ChatHistoryProtocol.Page("sid", 0, 0, 9, false, 0, "", "tail", 0, emptyList(),
                deliveries = mapOf("first" to QueuedMessages.Delivery.QUEUED), held = listOf(first))
            val updated = history.apply(page)
            assertEquals(terminal, updated.deliveries["first"])
            assertFalse(updated.display(listOf(first)).single().pending)
            assertTrue(unconfirmedAfterResync(updated, listOf(first)).isEmpty())
            assertEquals(QueuedMessages.Delivery.QUEUED, history.apply(page.copy(sid = "other")).deliveries["first"])
        }
    }

    @Test fun offPageDeliveryAndCancellationDoNotNeedTranscript() {
        val history = ChatHistory(sid = "sid", deliveries = mapOf("first" to QueuedMessages.Delivery.DELIVERED,
            "second" to QueuedMessages.Delivery.CANCELLED))
        assertEquals(listOf("Delivered", "Cancelled"), history.display().map { it.delivery })
        assertTrue(unconfirmedAfterResync(history, listOf(first, second)).isEmpty())
    }

    @Test fun duplicateIdsCollapseButDuplicateTextsDoNot() {
        val result = ChatHistory(held = listOf(first)).display(listOf(first, first, second))
        assertEquals(listOf("first", "second"), result.map { it.requestId })
        assertEquals(2, result.map { it.key }.toSet().size)
    }

    @Test fun nativeIntentionsStayDistinctFromIdenticalTranscriptOccurrences() {
        val native = QueuedMessages.SentPrompt("same", 1, sid = "sid", inode = 9, afterOffset = 100)
        val another = QueuedMessages.SentPrompt("same", 2, sid = "sid", inode = 9, afterOffset = 100)
        val sent = listOf(native, another)
        fun row(offset: Long) = ChatHistoryProtocol.Row("user", "same", "id$offset", "2099-01-01T00:00:00Z", offset, offset + 1)
        val history = ChatHistory(sid = "sid", inode = 9)
        val keys = sent.map { "@local:${it.localId}" }
        for (rows in listOf(emptyList(), listOf(row(101)), listOf(row(101), row(102)))) {
            val snapshot = history.copy(rows = rows)
            val result = snapshot.display(sent)
            assertEquals(rows.size + 2, result.size)
            assertEquals(keys, result.takeLast(2).map { it.key })
            assertEquals(keys, snapshot.display(sent).takeLast(2).map { it.key })
            assertTrue(result.take(rows.size).all { it.delivery == null })
            assertTrue(result.takeLast(2).all { !it.pending && it.requestId.isEmpty() && it.delivery == "Local · unconfirmed" })
            assertEquals(sent, unconfirmedAfterResync(snapshot, sent))
        }
        val threeRows = history.copy(rows = listOf(row(50), row(101), row(102)))
        assertEquals(5, threeRows.display(sent).size)
        assertEquals(3, threeRows.copy(sid = "other").display(sent).size)
        assertEquals(keys, threeRows.copy(inode = 10).display(sent).takeLast(2).map { it.key })
        assertEquals(keys, threeRows.display(sent.map { it.copy(sid = "") }).takeLast(2).map { it.key })
    }

    @Test fun nativeIntentionsSurviveHistoryRefreshAndDelayedOccurrences() {
        val native = QueuedMessages.SentPrompt("same", 1, sid = "sid", inode = 9, afterOffset = 100)
        val sent = listOf(native)
        val occurrence = ChatHistoryProtocol.Row("user", "same", "other-client", "", 101, 102)
        val reply = ChatHistoryProtocol.Row("assistant", "reply", "reply", "2099", 103, 104)
        val initial = ChatHistory(sid = "sid", inode = 9, offset = 100, start = 0)
        val delta = ChatHistoryProtocol.Page("sid", 100, 104, 9, false, 0, "", "delta", 0, listOf(occurrence, reply))
        val loaded = initial.apply(delta)
        val olderBase = ChatHistory(sid = "sid", inode = 9, offset = 104, start = 100, rows = listOf(occurrence, reply))
        val older = olderBase.apply(ChatHistoryProtocol.Page("sid", 0, 100, 9, false, 0, "", "older", 0,
            listOf(ChatHistoryProtocol.Row("user", "same", "delayed", "", 50, 51))))
        val refreshed = loaded.apply(delta.copy(mode = "tail", base = 0, inode = 10))
        val emptied = refreshed.apply(delta.copy(mode = "tail", base = 0, inode = 10, end = 0, rows = emptyList()))
        for (history in listOf(initial, loaded, older, refreshed, emptied, refreshed.resetSeed())) {
            val echo = history.display(sent).last()
            assertEquals("@local:${native.localId}", echo.key)
            assertEquals("Local · unconfirmed", echo.delivery)
            assertEquals(sent, unconfirmedAfterResync(history, sent))
        }
        assertEquals(listOf("same", "same", "reply", "same"), older.display(sent).map { it.text })
        assertEquals(listOf("same", "reply", "same"), loaded.display(sent).map { it.text })
        assertEquals(listOf("same", "reply", "same"), refreshed.display(sent).map { it.text })
    }
    @Test fun deliveredDisplayMatchesOnlyTrailingAsciiSpacesOneToOne() {
        val text = "x".repeat(200)
        val one = first.copy(text = "$text ", delivery = QueuedMessages.Delivery.DELIVERED)
        val two = one.copy(messageId = "second", sentAtMillis = 2)
        fun row(body: String, offset: Long) = ChatHistoryProtocol.Row("user", body, "row$offset", "", offset, offset + 1)
        val history = ChatHistory(sid = "sid", inode = 9, rows = listOf(row(text, 100)))
        assertEquals(1, history.display(listOf(one)).size)
        assertEquals(2, history.display(listOf(one, two)).size)
        assertEquals(2, history.copy(rows = listOf(row(text, 100), row(text, 101))).display(listOf(one, two)).size)
        assertEquals(2, history.copy(rows = listOf(row("$text\t", 100))).display(listOf(one)).size)
        assertEquals(2, history.copy(rows = listOf(row(text.dropLast(1), 100))).display(listOf(one)).size)
        assertEquals(2, history.copy(inode = 10).display(listOf(one)).size)
        assertEquals(2, history.copy(rows = listOf(row(text, 99))).display(listOf(one)).size)
        assertEquals(2, history.display(listOf(one.copy(delivery = QueuedMessages.Delivery.QUEUED))).size)
    }

    @Test fun differentSessionRecordsStayUnresolvedAndInvisible() {
        val native = QueuedMessages.SentPrompt("native old", 1, sid = "old", inode = 9)
        val managed = first.copy(text = "managed old", sid = "old")
        val blank = QueuedMessages.SentPrompt("pending", 3)
        val history = ChatHistory(sid = "new", inode = 9, deliveries = mapOf("first" to QueuedMessages.Delivery.DELIVERED))
        assertEquals(listOf("pending"), history.display(listOf(native, managed, blank)).map { it.text })
        assertEquals(listOf(native, managed, blank), unconfirmedAfterResync(history, listOf(native, managed, blank)))
        assertEquals(listOf(managed), unconfirmedAfterResync(history, listOf(managed.copy(delivery = QueuedMessages.Delivery.QUEUED))))
    }

}
