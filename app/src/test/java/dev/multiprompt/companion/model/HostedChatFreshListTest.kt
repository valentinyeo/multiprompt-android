package dev.multiprompt.companion.model

import dev.multiprompt.companion.reader.ChatHistory
import dev.multiprompt.companion.reader.ChatHistoryProtocol
import dev.multiprompt.companion.reader.TranscriptProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HostedChatFreshListTest {
    private val hostId = "vps"
    private val tmux = "live-android-2996"
    private val hostSid = "c001c001-2996-4000-8000-000000002996"
    private val nativeSid = "d002d002-2996-4000-8000-000000002996"
    private val newHostSid = "e003e003-2996-4000-8000-000000002996"
    private val probe = "a".repeat(64)

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
        ChatHistoryProtocol.Page(hostSid, 0, 100, inode, false, 2_000_000_000L, probe, "tail", 0, rows)

    private fun historyHeader(
        wireSid: String,
        id: Long = 1,
        base: Long = 0,
        end: Long = 100,
        inode: Long = 2,
        more: Int = 0,
        mode: String = "tail",
        oldest: Long = 0,
    ) = "@mp\t$id\thistory\tok\t$wireSid\t$base\t$end\t$inode\t$more\tclaude\t$tmux\t1730000000000000000\t$probe\t$mode\t$oldest"

    @Test
    fun interimRefreshKeepsHostSidWhenCachedRowStillCarriesNativeConversationId() {
        val before = live(hostSid)
        val cachedNative = live(nativeSid)
        val merged = RefreshMerge.merge(
            hostIds = listOf(hostId),
            currentSessions = listOf(before),
            cachedByHost = mapOf(hostId to listOf(cachedNative)),
            results = emptyMap(),
            final = false,
            currentUpdatingHostIds = setOf(hostId),
        )
        val stabilized = RefreshMerge.stabilizeHostedIdentity(listOf(before), merged.sessions, hostListFinal = false)
        assertEquals(hostSid, stabilized.single().hostSessionId)
        assertNotEquals(nativeSid, stabilized.single().hostSessionId)
    }

    @Test
    fun finalRefreshAdoptsNewHostSidForSameTmuxName() {
        val before = live(hostSid)
        val recreated = live(newHostSid)
        val merged = RefreshMerge.merge(
            hostIds = listOf(hostId),
            currentSessions = listOf(before),
            cachedByHost = emptyMap(),
            results = mapOf(hostId to RefreshMerge.HostResult(listOf(recreated))),
            final = true,
        )
        val stabilized = RefreshMerge.stabilizeHostedIdentity(listOf(before), merged.sessions, hostListFinal = true)
        assertEquals(newHostSid, stabilized.single().hostSessionId)
        assertNotEquals(hostSid, stabilized.single().hostSessionId)
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
            append(historyHeader(nativeSid))
            append("\n")
            append(JSONObject().put("role", "user").put("text", "after fresh start").put("id", "u1")
                .put("timestamp", "").put("offset", 0).put("end", 30))
            append("\n${TranscriptProtocol.END}\n")
        }
        val page = ChatHistoryProtocol.parse(
            JSONObject().put("id", 1).put("wire", wire).toString(),
            1,
            identity,
        )
        assertEquals(hostSid, page?.sid)
    }

    @Test
    fun hostedHistoryDeltaPollStillValidatesCachedSidAgainstHostSid() {
        val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, tmux, "/work", hostSid)
        val history = ChatHistory(sid = hostSid, inode = 2, offset = 100, mtime = 1_730_000_000_000_000_000L, probe = probe)
        val wire = historyHeader(nativeSid, id = 2, base = 100, end = 120, inode = 2, more = 0, mode = "delta", oldest = 0) + "\n" +
            JSONObject().put("role", "user").put("text", "tail").put("id", "u2")
                .put("timestamp", "").put("offset", 100).put("end", 120) +
            "\n${TranscriptProtocol.END}\n"
        val page = ChatHistoryProtocol.parse(JSONObject().put("id", 2).put("wire", wire).toString(), 2, identity)!!
        val updated = history.apply(page)
        val request = JSONObject(ChatHistoryProtocol.request(3, identity, updated))
        assertEquals(hostSid, request.getString("cached_sid"))
        assertEquals(120L, request.getLong("offset"))
    }

    @Test
    fun plainTmuxTranscriptParseLeavesWireSidUnchanged() {
        val plainTmux = "claude-work-2996"
        val transcriptSid = "821fb507-2781-4e48-8b6d-debd6e2da3f3"
        val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, plainTmux, "/work", transcriptSid)
        val wire = "@mp\t1\thistory\tok\t$transcriptSid\t0\t80\t1278069\t0\tclaude\t$plainTmux\t1793653993390199707\t$probe\ttail\t0\n" +
            JSONObject().put("role", "user").put("text", "plain").put("id", "u4")
                .put("timestamp", "").put("offset", 0).put("end", 40) +
            "\n${TranscriptProtocol.END}\n"
        val page = ChatHistoryProtocol.parse(JSONObject().put("id", 1).put("wire", wire).toString(), 1, identity)
        assertEquals(transcriptSid, page?.sid)
    }
}