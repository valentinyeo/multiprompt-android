package dev.multiprompt.companion.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClearedChatTest {
    @get:Rule val temporary = TemporaryFolder()

    private val sid = "cleared-chat-proof"
    private val oldId = "clearedmessage000001"
    private val newId = "clearedmessage000002"

    private fun stamp(seconds: Double): Long = (seconds * 1_000_000_000L).toLong()

    private fun row(offset: Long, text: String, id: String) =
        ChatHistoryProtocol.Row("user", text, id, "", offset, offset + 10)

    private fun timed(offset: Long, text: String, id: String, at: String) =
        ChatHistoryProtocol.Row("user", text, id, at, offset, offset + 10)

    private fun page(rows: List<ChatHistoryProtocol.Row>, inode: Long, at: Double, probe: String = "a".repeat(64)) =
        ChatHistoryProtocol.Page(sid, 0, 100, inode, false, stamp(at), probe, "tail", 0, rows)

    private fun replica(vararg rows: MessageProtocol.Row) = MessageReplica(1, 1, rows.associateBy { it.id })

    private fun message(id: String, text: String, state: MessageProtocol.State, created: Double) =
        MessageProtocol.Row(id, "claude", sid, text, 1, "attempt", state, null, null, 1, created)

    private fun before(at: Double = 1_000.0) = ChatHistory(sid = sid, inode = 1, offset = 100, mtime = stamp(at),
        rows = listOf(row(0, "old question", "old"), row(20, "old answer", "oldanswer")))

    private fun cleared(at: Double = 2_000.0) = before().apply(page(listOf(row(0, "new question", "new")), inode = 2, at = at))

    @Test
    fun aDeadlineFailedMessageShowsNotSentInsteadOfAutoRetrying() {
        val row = message(oldId, "CLEARED_BEFORE_2474", MessageProtocol.State.FAILED, 1_000.0).copy(receipt = "not_sent")
        assertFalse(SendRecovery.due(row, recovered = 0, connected = true))
        val drawn = before().projectMessages(replica(row), now = 3_000.0).single { it.text == "CLEARED_BEFORE_2474" }
        assertEquals("Not sent", drawn.delivery)
    }

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

    @Test
    fun aClearStillReplacesRowsThatLookNewerOnScreen() {
        val shown = ChatHistory(sid = sid, inode = 1, offset = 100, mtime = stamp(1_600.0),
            rows = listOf(timed(0, "old question", "old", "2026-11-21T16:00:00.000Z")))
        val afterClear = shown.apply(page(listOf(timed(0, "new question", "new", "2026-11-21T15:00:00.000Z")), inode = 2, at = 2_000.0))
        assertEquals(2L, afterClear.inode)
        assertEquals(2_000.0, afterClear.clearedAt, 0.0)
        assertEquals(listOf("new question"), afterClear.messages.map { it.text })
    }

    @Test
    fun aTranscriptSwapStillAppliesWhenClearMtimeDoesNotAdvance() {
        val shown = ChatHistory(sid = sid, inode = 1, offset = 100, mtime = stamp(2_000.0),
            rows = listOf(timed(0, "VERIFY_READY_1008", "old", "2026-11-21T16:00:00.000Z")))
        val afterClear = shown.apply(page(listOf(timed(0, "CLEARED_READY_2474", "new", "2026-11-21T15:00:00.000Z")), inode = 2, at = 1_900.0, probe = "b".repeat(64)))
        assertEquals(2L, afterClear.inode)
        assertEquals(2_000.0, afterClear.clearedAt, 0.0)
        assertEquals(listOf("CLEARED_READY_2474"), afterClear.messages.map { it.text })
    }

    @Test
    fun aStaleInodeTailCannotReplaceAClearedTranscript() {
        val shown = ChatHistory(sid = sid, inode = 2, offset = 100, mtime = stamp(2_000.0), clearedAt = 2_000.0,
            rows = listOf(timed(0, "CLEARED_READY_2474", "new", "2026-11-21T15:00:00.000Z")))
        val stale = page(listOf(timed(0, "CLEARED_BEFORE_2474", "old", "2026-11-21T16:00:00.000Z")), inode = 1, at = 1_500.0)
        val after = shown.apply(stale)
        assertEquals(shown.messages.map { it.text }, after.messages.map { it.text })
        assertEquals(2L, after.inode)
    }

    @Test
    fun theClearBoundarySurvivesTheCachedChatAfterARestart() {
        val cache = ChatHistoryCache(temporary.newFolder("cleared-cache"))
        cache.save("host/tmux", cleared())
        val restored = requireNotNull(cache.restore("host/tmux"))
        assertEquals(2_000.0, restored.clearedAt, 0.0)
        val drawn = restored.projectMessages(replica(message(oldId, "OLD unsent", MessageProtocol.State.FAILED, 1_500.0)), now = 3_000.0)
        assertEquals(listOf("new question"), drawn.map { it.text })
    }
}
