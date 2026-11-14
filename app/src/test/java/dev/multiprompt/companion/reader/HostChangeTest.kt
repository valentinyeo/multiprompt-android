package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostChangeTest {
    private val sid = "6f353a80-d3df-4e44-88aa-edcc6c2b8999"

    @Test fun eachChoiceSendsTheDesktopRequest() {
        val model = JSONObject(HostChange.SONNET.line(7, sid))
        assertEquals(listOf(7, "set_model", "claude", sid, "sonnet"),
            listOf(model.getInt("id"), model.getString("op"), model.getString("provider"), model.getString("sid"), model.getString("model")))
        assertEquals("high", JSONObject(HostChange.HIGH.line(8, sid)).getString("effort"))
        val mode = JSONObject(HostChange.ASK.line(9, sid))
        assertEquals("set_permission_mode" to "manual", mode.getString("op") to mode.getString("mode"))
        assertEquals("bypassPermissions", JSONObject(HostChange.BYPASS.line(10, sid)).getString("mode"))
    }

    @Test fun theMenuMatchesTheDesktopPlusMenu() {
        val host = HostChange.sections(true).map { (title, changes) -> title to changes.map { it.label } }
        assertEquals(listOf(
            "Model" to listOf("Opus", "Sonnet", "Haiku"),
            "Effort" to listOf("Low", "Medium", "High"),
            "Permission level" to listOf("Ask", "Accept edits", "Plan", "Bypass"),
        ), host)
        assertEquals(listOf("Model", "Effort"), HostChange.sections(false).map { it.first })
    }

    @Test fun noticesMatchTheDesktop() {
        assertEquals(listOf("Model: Opus", "Effort: Medium", "Accept edits on", "Plan on"),
            listOf(HostChange.OPUS, HostChange.MEDIUM, HostChange.EDITS, HostChange.PLAN).map { it.notice })
        assertEquals("Could not change chat settings", HostChange.HAIKU.failure)
        assertEquals("Could not change permission mode; try again", HostChange.BYPASS.failure)
    }

    @Test fun theActiveChoiceFollowsTheHostSettings() {
        val settings = HostSettings("opus", "high", "acceptEdits", true)
        assertEquals(listOf(HostChange.OPUS, HostChange.HIGH, HostChange.EDITS),
            HostChange.entries.filter { it.active(settings, "Sonnet 4.5", "low") })
    }

    @Test fun anEmptyHostModelFallsBackToTheTranscriptModel() {
        val settings = HostSettings("", "", "manual", false)
        assertTrue(HostChange.HAIKU.active(settings, "Haiku 4.5", null))
        assertTrue(HostChange.ASK.active(settings, null, null))
        assertFalse(HostChange.OPUS.active(settings, "Opusculum", null))
        assertTrue(HostChange.OPUS.active(null, "claude-opus-4-1", null))
        assertTrue(HostChange.MEDIUM.active(null, null, "Medium"))
        assertFalse(HostChange.PLAN.active(null, null, null))
    }

    @Test fun aCodexChatListsTheFourCodexModels() {
        assertEquals(listOf("Model" to listOf("Sol", "Terra", "Luna", "Astra")),
            HostChange.sections(true, "codex").map { (title, changes) -> title to changes.map { it.label } })
        assertTrue(HostChange.sections(false, "codex").isEmpty())
    }

    @Test fun pickingACodexModelSendsTheDesktopRequest() {
        listOf(HostChange.SOL to "sol", HostChange.TERRA to "terra", HostChange.LUNA to "luna", HostChange.ASTRA to "astra").forEach { (change, value) ->
            val line = JSONObject(change.line(11, sid))
            assertEquals(listOf("set_model", "codex", sid, value), listOf(line.getString("op"), line.getString("provider"), line.getString("sid"), line.getString("model")))
            assertFalse(line.has("effort"))
        }
        assertEquals(listOf("Model: Sol", "Model: Terra", "Model: Luna", "Model: Astra"),
            listOf(HostChange.SOL, HostChange.TERRA, HostChange.LUNA, HostChange.ASTRA).map { it.notice })
        assertEquals("Could not change chat settings", HostChange.TERRA.failure)
    }

    @Test fun theActiveCodexModelFollowsTheHostSettings() {
        val settings = HostSettings("luna", "", "", true)
        assertEquals(listOf(HostChange.LUNA), HostChange.entries.filter { it.provider == "codex" && it.active(settings, "Sonnet 4.5", "low") })
        assertTrue(HostChange.entries.filter { it.provider == "codex" }.none { it.active(HostSettings("", "", "", false), "Opus 4.7", null) })
        assertTrue(HostChange.entries.filter { it.provider == "codex" }.none { it.active(null, null, null) })
    }

    @Test fun aClaudeChatKeepsOnlyItsOwnModels() {
        val models = HostChange.sections(true).first { it.first == "Model" }.second
        assertEquals(models, HostChange.sections(true, "claude").first { it.first == "Model" }.second)
        assertTrue(models.all { it.provider == "claude" })
        assertTrue(listOf("Sol", "Terra", "Luna", "Astra").none { name -> models.any { it.label == name } })
        assertEquals("/model sonnet", HostChange.SONNET.command)
        assertNull(HostChange.SOL.command)
        assertEquals("claude", JSONObject(HostChange.OPUS.line(1, sid)).getString("provider"))
    }

    @Test fun onlyClaudeAndCodexChatsGetHostSettings() {
        assertEquals("claude", HostChange.providerOf(AgentKind.CLAUDE))
        assertEquals("codex", HostChange.providerOf(AgentKind.CODEX))
        listOf(AgentKind.PI, AgentKind.HAX, AgentKind.CURSOR, AgentKind.KIMI, AgentKind.OTHER).forEach { assertNull(HostChange.providerOf(it)) }
    }
}
