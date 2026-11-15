package dev.multiprompt.companion.reader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PiModelsTest {
    private val sid = "6f353a80-d3df-4e44-88aa-edcc6c2b8999"
    private val deepseek = PiModel("deepseek-ai/DeepSeek-V4-Pro-0813", "baseten", "1.0M")
    private val sol = PiModel("gpt-5.6-sol", "openai-codex", "272K")

    @Test fun requestAsksTheCompletionHelperForPiModels() {
        assertEquals("7\tmodels\tpi\t-\t-\t\n", PiModels.request(7))
        assertThrows(IllegalArgumentException::class.java) { PiModels.request(0) }
    }

    @Test fun parseReadsPiRowsAndDropsJunk() {
        val wire = "@mp\t7\tmodels\tok\t/home/me\t2\n" +
            "M\tdeepseek-ai/DeepSeek-V4-Pro-0813\tbaseten\t1.0M\n" +
            "M\tgpt-5.6-sol\topenai-codex\t272K\n" +
            "@@MP_END@@\n"
        assertEquals(listOf(deepseek, sol), PiModels.parse(wire, 7))
        assertEquals("/model openai-codex/gpt-5.6-sol", PiModels.command(sol))
        assertEquals(emptyList<PiModel>(), PiModels.parse("@mp\t7\tmodels\terror\t-\t0\n@@MP_END@@\n", 7))
        assertNull(PiModels.parse("not a frame\n", 7))
        assertNull(PiModels.parse("@mp\t8\tmodels\tok\t-\t0\n@@MP_END@@\n", 7))
        assertNull(PiModels.parse("@mp\t7\tslash\tok\t-\t0\n@@MP_END@@\n", 7))
    }

    @Test fun bareNamesStayUsableAndJunkRowsAreDropped() {
        val bare = PiModel("gpt-oss-120b", "")
        assertEquals("gpt-oss-120b", bare.reference)
        assertEquals("/model gpt-oss-120b", PiModels.command(bare))
        val wire = "@mp\t1\tmodels\tok\t-\t2\nX\tignored\t\t\nM\t\tempty\t\nM\thas space\topenai\t\n@@MP_END@@\n"
        assertEquals(emptyList<PiModel>(), PiModels.parse(wire, 1))
    }

    @Test fun hostRequestCarriesTheProviderAndTheModelReference() {
        val line = JSONObject(PiModels.line(42, sid, deepseek))
        assertEquals(42, line.getInt("id"))
        assertEquals("set_model", line.getString("op"))
        assertEquals("pi", line.getString("provider"))
        assertEquals(sid, line.getString("sid"))
        assertEquals("baseten", line.getString("provider_name"))
        assertEquals("baseten/deepseek-ai/DeepSeek-V4-Pro-0813", line.getString("model"))
        assertEquals("Model: deepseek-ai/DeepSeek-V4-Pro-0813", PiModels.notice(deepseek))
        val quoted = JSONObject(PiModels.line(1, "sid\"1", deepseek))
        assertEquals("sid\"1", quoted.getString("sid"))
    }

    @Test fun duplicateReferencesKeepTheFirstAndTheListStopsAtTheCap() {
        val wire = "@mp\t1\tmodels\tok\t-\t2\n" +
            "M\tgpt-5.6-sol\topenai-codex\tfirst\n" +
            "M\tgpt-5.6-sol\topenai-codex\tsecond\n" +
            "@@MP_END@@\n"
        assertEquals(listOf(PiModel("gpt-5.6-sol", "openai-codex", "first")), PiModels.parse(wire, 1))
        val rows = (1..PiModels.MAX_MODELS + 1).joinToString("") { "M\tm$it\tp$it\t\n" }
        val parsed = requireNotNull(PiModels.parse("@mp\t3\tmodels\tok\t-\t${PiModels.MAX_MODELS + 1}\n$rows@@MP_END@@\n", 3))
        assertEquals(PiModels.MAX_MODELS, parsed.size)
        assertEquals("m1", parsed.first().name)
        assertEquals("m${PiModels.MAX_MODELS}", parsed.last().name)
    }

    @Test fun theMenuMarksTheCurrentModelAndSaysWhatHappened() {
        assertTrue(PiModels.selected(deepseek, "baseten/deepseek-ai/DeepSeek-V4-Pro-0813"))
        assertTrue(PiModels.selected(deepseek, " deepseek-ai/DeepSeek-V4-Pro-0813 "))
        assertFalse(PiModels.selected(deepseek, "baseten"))
        assertFalse(PiModels.selected(deepseek, " "))
        assertEquals("✓", PiModels.trailing(deepseek, deepseek.reference))
        assertEquals("baseten", PiModels.trailing(deepseek, null))
        assertEquals("1.0M", PiModels.trailing(PiModel("gpt-oss-120b", "", "1.0M"), null))
        assertEquals("Model: gpt-5.6-sol", PiModels.resultMessage(MessageActionResult.Applied, sol))
        assertEquals(PiModels.STARTING, PiModels.resultMessage(MessageActionResult.Rejected("Chat is not ready"), sol))
        assertEquals(PiModels.STARTING, PiModels.resultMessage(MessageActionResult.Rejected("Chat is unavailable"), sol))
        assertEquals(PiModels.SWITCHED, PiModels.resultMessage(MessageActionResult.Rejected("The agent host is not running"), sol))
        assertEquals(PiModels.SWITCHED, PiModels.resultMessage(MessageActionResult.Unknown, sol))
    }
}
