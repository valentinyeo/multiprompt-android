package dev.multiprompt.companion.reader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
