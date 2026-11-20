package dev.multiprompt.companion.model

import dev.multiprompt.companion.reader.ChatHistory
import dev.multiprompt.companion.reader.ChatHistoryProtocol
import dev.multiprompt.companion.reader.TranscriptProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HostedChatFreshListTest {
    private val hostId = "vps"
    private val tmux = "live-android-2996"
    private val hostSid = "c001c001-2996-4000-8000-000000002996"
    private val nativeSid = "d002d002-2996-4000-8000-000000002996"

    private fun live(hostSessionId: String) = TmuxSession(
        hostId = hostId,
        name = tmux,
        windows = 1,
        attachedClients = 0,
        lastActivityEpochSeconds = 2_000,
        paneCommand = "sleep",
        hostSessionId = hostSessionId,
        hostProvider = "claude",
    )

    private fun row(text: String, id: String = "row") =
        ChatHistoryProtocol.Row("user", text, id, "", 0, 40)

    private fun clearPage(rows: List<ChatHistoryProtocol.Row>, inode: Long) =
        ChatHistoryProtocol.Page(hostSid, 0, 100, inode, false, 2_000_000_000L, "a".repeat(64), "tail", 0, rows)

    @Test
    fun listRefreshKeepsOneRowKeyedOnHostSidAfterFreshConversationId() {
        val before = live(hostSid)
        val afterWrongSid = live(nativeSid)
        val merged = RefreshMerge.merge(
            hostIds = listOf(hostId),
            currentSessions = listOf(before),
            cachedByHost = emptyMap(),
            results = mapOf(hostId to RefreshMerge.HostResult(listOf(afterWrongSid))),
            final = true,
        )
        val stabilized = RefreshMerge.stabilizeHostedIdentity(listOf(before), merged.sessions)
        assertEquals(1, stabilized.size)
        assertEquals(tmux, stabilized.single().name)
        assertEquals(hostSid, stabilized.single().hostSessionId)
        assertNotEquals(nativeSid, stabilized.single().hostSessionId)
    }

    @Test
    fun plainClearReplacesTranscriptWithoutOldMessages() {
        val before = ChatHistory(
            sid = hostSid,
            inode = 1,
            offset = 100,
            rows = listOf(row("PINEAPPLE secret", "old")),
        )
        val after = before.apply(clearPage(listOf(row("new question after clear", "new")), inode = 2))
        assertEquals(hostSid, after.sid)
        assertEquals(listOf("new question after clear"), after.messages.map { it.text })
    }

    @Test
    fun hostedHistoryWireKeepsHostSidNotNativeConversationId() {
        val identity = ChatHistoryProtocol.Identity(
            agent = AgentKind.CLAUDE,
            tmux = tmux,
            cwd = "/work",
            sid = hostSid,
        )
        val wire = buildString {
            append("@mp\t1\thistory\tok\t$nativeSid\t0\t100\t2\t1730000000000000000\tclaude\t$tmux\t0\t")
            append("a".repeat(64))
            append("\ttail\t0\n")
            append(org.json.JSONObject().put("role", "user").put("text", "after fresh start").put("id", "u1")
                .put("timestamp", "").put("offset", 0).put("end", 30))
            append("\n${TranscriptProtocol.END}\n")
        }
        val page = ChatHistoryProtocol.parse(
            org.json.JSONObject().put("id", 1).put("wire", wire).toString(),
            1,
            identity,
        )
        assertEquals(hostSid, page?.sid)
    }
}