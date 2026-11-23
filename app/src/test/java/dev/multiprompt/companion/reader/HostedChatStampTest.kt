package dev.multiprompt.companion.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostedChatStampTest {
    private val sid = "05825187-f4b7-4fec-84c9-16726b3b5258"
    private val prompt = "From Fleet Tools (solo-wake): Continue draining your board per /solo-dev: next " +
        "https://app.hypertask.ai/detail/project-15/9406 (HTPR-9406, AI Review). Already mid-ticket on a job? Finish that first, then this."

    private fun seconds(value: String) = java.time.LocalDateTime.parse(value)
        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() / 1000.0

    private fun card(position: Int, id: String, text: String, created: String) = MessageProtocol.Row(
        id, "claude", sid, text, position.toLong(), "attempt0123456789", MessageProtocol.State.ACCEPTED,
        null, "receipt-$position", 1, seconds(created),
    )

    private fun historyRow(role: String, text: String, id: String, stamp: String, offset: Long) =
        ChatHistoryProtocol.Row(role, text, id, stamp, offset, offset + text.length + 10)

    private fun history(rows: List<ChatHistoryProtocol.Row>) = ChatHistory(
        sid = sid, inode = 2201715, offset = 4965515, start = 3716988, mtime = 1795269348488684647,
        probe = "a".repeat(64), rows = rows,
    )

    private fun replica(vararg rows: MessageProtocol.Row) =
        MessageReplica(epoch = 1, cursor = rows.size.toLong(), messages = rows.associateBy { it.id })

    private val oldCards = listOf(
        card(198, "mp-4425c9b0ddec46cb98ebdefb6ac4fbef", prompt, "2026-11-18T20:03:03.262901"),
        card(199, "mp-4f3b41c3b50b41228c4a59f1cad9c1a2", prompt, "2026-11-18T20:43:03.123833"),
        card(200, "mp-be8c6aad27e248e69c12c0d64f03214f", prompt, "2026-11-18T21:23:15.406146"),
        card(201, "mp-cb5f343c6e834db8971326a9f63daf90", prompt, "2026-11-18T21:53:11.760920"),
    )
    private val newestCard = card(249, "304b762d-4730-4e3f-bbc8-05f333ca8d2e",
        "https://x.com/Tristan0x/status/2123802919455907947", "2026-11-21T14:53:30.328664")

    @Test
    fun aHostedRowStampWithoutAZoneStillReads() {
        assertNotNull(MessageIdentity.seconds("2026-11-21T14:55:48.488890"))
        assertTrue(
            MessageIdentity.seconds("2026-11-18T21:53:11.760920")!! <
                MessageIdentity.seconds("2026-11-21T14:55:48.488890")!!,
        )
    }

    @Test
    fun anOlderHostedTailCannotReplaceTheNewerOne() {
        val shown = history(listOf(historyRow("assistant", "newest", "history-a", "2026-11-21T14:55:48.488890", 100)))
        val older = history(listOf(historyRow("assistant", "old", "history-b", "2026-11-18T21:53:11.760920", 100)))

        assertTrue(ChatHistory.olderThan(shown, older))
        assertFalse(ChatHistory.olderThan(older, shown))
    }

    @Test
    fun anOldDesktopPromptOutsideTheTailIsNotPinnedToTheBottom() {
        val rows = listOf(
            historyRow("user", newestCard.text, newestCard.id, "2026-11-21T14:53:30.328664", 4_900_000),
            historyRow("assistant", "We already do three of the post's four tricks.", "history-af58a66a27964724a0178276ac82d175",
                "2026-11-21T14:55:48.488890", 4_950_000),
        )

        val projected = history(rows).projectMessages(replica(*(oldCards + newestCard).toTypedArray()))

        assertEquals("We already do three of the post's four tricks.", projected.last().text)
        assertFalse(projected.any { it.text == prompt })
        assertEquals(1, projected.count { it.text == newestCard.text })
    }

    @Test
    fun anOldCardWithoutAStampStillStaysBehindTheSettledRow() {
        val unstamped = oldCards.map { it.copy(created = 0.0) }
        val rows = listOf(
            historyRow("user", newestCard.text, newestCard.id, "", 4_900_000),
            historyRow("assistant", "We already do three of the post's four tricks.", "history-af58a66a27964724a0178276ac82d175", "", 4_950_000),
        )

        val projected = history(rows).projectMessages(replica(*(unstamped + newestCard).toTypedArray()))

        assertEquals("We already do three of the post's four tricks.", projected.last().text)
        assertFalse(projected.any { it.text == prompt })
    }

    @Test
    fun anUndeliveredCardStillReachesTheBottom() {
        val failed = card(100, "mp-failed0000000000000000000000", "send this again", "2026-11-18T09:00:00.000000")
            .copy(state = MessageProtocol.State.FAILED)
        val rows = listOf(
            historyRow("assistant", "We already do three of the post's four tricks.", "history-af58a66a27964724a0178276ac82d175",
                "2026-11-21T14:55:48.488890", 4_950_000),
        )

        val projected = history(rows).projectMessages(replica(failed))

        assertEquals("send this again", projected.last().text)
    }
}
