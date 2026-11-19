package dev.multiprompt.companion.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ClearedChatTest {
    private val sid = "cleared-chat-proof"
    private val oldId = "clearedmessage000001"
    private val newId = "clearedmessage000002"

    private fun stamp(seconds: Double): Long = (seconds * 1_000_000_000L).toLong()

    private fun row(offset: Long, text: String, id: String) =
        ChatHistoryProtocol.Row("user", text, id, "", offset, offset + 10)

    private fun page(rows: List<ChatHistoryProtocol.Row>, inode: Long, at: Double) =
        ChatHistoryProtocol.Page(sid, 0, 100, inode, false, stamp(at), "a".repeat(64), "tail", 0, rows)

    private fun replica(vararg rows: MessageProtocol.Row) = MessageReplica(1, 1, rows.associateBy { it.id })

    private fun message(id: String, text: String, state: MessageProtocol.State, created: Double) =
        MessageProtocol.Row(id, "claude", sid, text, 1, "attempt", state, null, null, 1, created)

    private fun before(at: Double = 1_000.0) = ChatHistory(sid = sid, inode = 1, offset = 100, mtime = stamp(at),
        rows = listOf(row(0, "old question", "old"), row(20, "old answer", "oldanswer")))

    private fun cleared(at: Double = 2_000.0) = before().apply(page(listOf(row(0, "new question", "new")), inode = 2, at = at))

    @Test
    fun aFailedMessageFromBeforeTheClearIsNotDrawnAfterIt() {
        val drawn = cleared().projectMessages(replica(message(oldId, "OLD unsent", MessageProtocol.State.FAILED, 1_500.0)), now = 3_000.0)
        assertEquals(listOf("new question"), drawn.map { it.text })
    }

    @Test
    fun aQueuedMessageFromBeforeTheClearIsNotDrawnAfterIt() {
        val drawn = cleared().projectMessages(replica(message(oldId, "OLD unsent", MessageProtocol.State.QUEUED, 1_990.0)), now = 3_000.0)
        assertEquals(listOf("new question"), drawn.map { it.text })
    }

    @Test
    fun anUnsentMessageTypedAfterTheClearStays() {
        val drawn = cleared().projectMessages(replica(message(newId, "AFTER unsent", MessageProtocol.State.FAILED, 2_100.0)), now = 3_000.0)
        assertEquals(listOf("new question", "AFTER unsent"), drawn.map { it.text })
    }
}
