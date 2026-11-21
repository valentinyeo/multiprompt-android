package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryStaleTailTest {
    private val liveSid = "2f2b1c5e-8c1f-4a4c-9a4e-0f0f0f0f0f0f"
    private val oldSid = "18b6c0de-1111-4000-8000-000000000000"
    private val probe = "c".repeat(64)
    private val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "live-c2a89c7", "/home/me/multiprompt", liveSid)

    private fun row(offset: Long, role: String, timestamp: String, text: String): String = JSONObject()
        .put("role", role).put("text", text).put("id", "row-$offset").put("timestamp", timestamp)
        .put("offset", offset).put("end", offset + 10).toString()

    private fun page(
        id: Long,
        sid: String = liveSid,
        inode: Long = 1278069,
        base: Long = 0,
        end: Long,
        oldest: Long = 0,
        mode: String = "tail",
        rows: List<String> = emptyList(),
    ): ChatHistoryProtocol.Page {
        val header = listOf("@mp", id, "history", "ok", sid, base, end, inode, 0, "claude", "live-c2a89c7",
            1793653993390199707L, probe, mode, oldest).joinToString("\t")
        val wire = (listOf(header) + rows + TranscriptProtocol.END).joinToString("\n") + "\n"
        return requireNotNull(ChatHistoryProtocol.parse(JSONObject().put("id", id).put("wire", wire).toString(), id, identity))
    }

    private fun liveTail(id: Long) = page(id, end = 40, rows = listOf(
        row(0, "user", "2026-11-21T16:32:10.000Z", "ship the reader fix"),
        row(10, "assistant", "2026-11-21T16:33:40.000Z", "done, it is live"),
    ))

    private fun staleTail(id: Long) = page(id, sid = oldSid, inode = 991, end = 40, rows = listOf(
        row(0, "user", "2026-11-18T09:12:00.000Z", "old question"),
        row(10, "assistant", "2026-11-18T09:13:00.000Z", "old answer"),
    ))

    @Test
    fun aStaleSnapshotCannotReplaceTheNewerHistoryOnScreen() {
        val shown = ChatHistory().apply(liveTail(1))
        assertEquals(listOf("ship the reader fix", "done, it is live"), shown.messages.map { it.text })

        val afterStale = shown.apply(staleTail(2))

        assertEquals(shown, afterStale)
        assertEquals(liveSid, afterStale.sid)
        assertEquals(listOf("ship the reader fix", "done, it is live"), afterStale.messages.map { it.text })
    }

    @Test
    fun aRepeatedStaleSnapshotStillLeavesTheFreshHistoryAlone() {
        val shown = ChatHistory().apply(liveTail(1))
        val once = shown.apply(staleTail(2))
        assertEquals(once, once.apply(staleTail(3)))
        assertEquals(listOf("ship the reader fix", "done, it is live"), once.apply(staleTail(3)).messages.map { it.text })
    }

    @Test
    fun aNewerTailStillReplacesWhatIsOnScreen() {
        val shown = ChatHistory().apply(liveTail(1))
        val next = shown.apply(page(2, end = 60, rows = listOf(
            row(40, "user", "2026-11-21T16:41:00.000Z", "one more thing"),
            row(50, "assistant", "2026-11-21T16:42:00.000Z", "on it"),
        )))

        assertEquals(60L, next.offset)
        assertEquals(listOf("one more thing", "on it"), next.messages.map { it.text })
    }

    @Test
    fun pagingAnOlderWindowKeepsTheNewestRows() {
        val shown = ChatHistory().apply(page(1, base = 20, end = 40, oldest = 20, rows = listOf(
            row(20, "user", "2026-11-21T16:32:10.000Z", "ship the reader fix"),
            row(30, "assistant", "2026-11-21T16:33:40.000Z", "done, it is live"),
        )))
        val older = shown.apply(page(2, base = 10, end = 20, oldest = 10, mode = "older", rows = listOf(
            row(10, "user", "2026-11-21T16:30:00.000Z", "earlier question"),
        )))

        assertEquals(3, older.messages.size)
        assertEquals("earlier question", older.messages.first().text)
        assertEquals("done, it is live", older.messages.last().text)
        assertEquals(10L, older.start)
        assertTrue(older.offset == 40L)
    }

    @Test
    fun aSnapshotWithoutTimestampsIsStillLoaded() {
        val shown = ChatHistory().apply(liveTail(1))
        val untimed = shown.apply(page(2, sid = oldSid, inode = 991, end = 20, rows = listOf(
            row(0, "user", "", "no stamp"),
        )))

        assertEquals(listOf("no stamp"), untimed.messages.map { it.text })
    }
}
