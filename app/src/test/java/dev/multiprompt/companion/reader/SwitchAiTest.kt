package dev.multiprompt.companion.reader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchAiTest {
    private val sid = "6f353a80-d3df-4e44-88aa-edcc6c2b8999"
    private val next = "0a1b2c3d-d3df-4e44-88aa-edcc6c2b8999"

    private fun reply(result: String, id: Long = 1, protocol: Int = 1) =
        """{"id":$id,"message_protocol":$protocol,"result":$result}"""

    @Test fun theRequestIsTheDesktopShape() {
        val line = SwitchAi.query(1, "claude", sid, "codex")
        assertTrue(line.endsWith("\n"))
        assertEquals(1, line.count { it == '\n' })
        val query = JSONObject(line)
        assertEquals(setOf("op", "id", "provider", "sid", "to"), query.keys().asSequence().toSet())
        assertEquals("switch_ai", query.getString("op"))
        assertEquals(1, query.getInt("id"))
        assertEquals("claude", query.getString("provider"))
        assertEquals(sid, query.getString("sid"))
        assertEquals("codex", query.getString("to"))
    }

    @Test fun theMenuOffersEveryOtherAi() {
        assertEquals(listOf("codex", "pi"), SwitchAi.targets("claude").map { it.provider })
        assertEquals(listOf("claude", "pi"), SwitchAi.targets("codex").map { it.provider })
        assertEquals(listOf("claude", "codex"), SwitchAi.targets("pi").map { it.provider })
        assertEquals(listOf("Claude", "Codex", "Pi"), SwitchAi.targets(null).map { it.label })
    }

    @Test fun onlyClaudeCodexAndPiChatsCanSwitch() {
        listOf("claude", "codex", "pi").forEach { assertTrue(it, SwitchAi.supported(it, sid)) }
        listOf("hax", "cursor", "kimi", "Claude", "").forEach { assertFalse(it, SwitchAi.supported(it, sid)) }
        assertFalse(SwitchAi.supported(null, sid))
        assertFalse(SwitchAi.supported("claude", ""))
    }

    @Test fun hostileIdsAndTargetsNeverReachTheRequest() {
        listOf("a b", "a\nb", "a\"b", "a;rm -rf /", "../x", "a'b", "x".repeat(81)).forEach {
            assertThrows(IllegalArgumentException::class.java) { SwitchAi.query(1, "claude", it, "codex") }
        }
        listOf("claude", "hax", "codex;ls", "", "Codex").forEach {
            assertThrows(IllegalArgumentException::class.java) { SwitchAi.query(1, "claude", sid, it) }
        }
        assertThrows(IllegalArgumentException::class.java) { SwitchAi.query(0, "claude", sid, "codex") }
    }

    @Test fun aSwitchedReplyCarriesTheNewIdentity() {
        val outcome = SwitchAi.outcome(reply("""{"status":"switched","provider":"codex","sid":"$next","tmux":"live-x","start":"started"}"""), 1, "codex")
        assertEquals(SwitchAi.Outcome.Switched("codex", next), outcome)
    }

    @Test fun aSwitchedReplyForAnotherAiOrBadIdIsUnknown() {
        assertEquals(SwitchAi.Outcome.Unknown, SwitchAi.outcome(reply("""{"status":"switched","provider":"pi","sid":"$next"}"""), 1, "codex"))
        assertEquals(SwitchAi.Outcome.Unknown, SwitchAi.outcome(reply("""{"status":"switched","provider":"codex","sid":"a b"}"""), 1, "codex"))
    }

    @Test fun busyRefusedAndErrorAreFailures() {
        val busy = SwitchAi.outcome(reply("""{"status":"busy","reason":"chat_busy"}"""), 1, "codex")
        assertEquals("Switch AI: wait for this reply to finish", (busy as SwitchAi.Outcome.Failed).message)
        listOf("""{"status":"refused","reason":"host_disabled"}""", """{"status":"unavailable","reason":"host_closed"}""").forEach {
            assertEquals("Switch AI is not available for this chat", (SwitchAi.outcome(reply(it), 1, "codex") as SwitchAi.Outcome.Failed).message)
        }
        assertTrue(SwitchAi.outcome(reply("""{"status":"error","reason":"switch_failed"}"""), 1, "codex") is SwitchAi.Outcome.Failed)
    }

    @Test fun wrongEnvelopesAreNotAnAnswer() {
        val none = SwitchAi.Outcome.Failed(SwitchAi.NO_ANSWER)
        assertEquals(none, SwitchAi.outcome("not json", 1, "codex"))
        assertEquals(none, SwitchAi.outcome(reply("""{"status":"switched"}""", id = 2), 1, "codex"))
        assertEquals(none, SwitchAi.outcome(reply("""{"status":"switched"}""", protocol = 2), 1, "codex"))
        assertEquals(none, SwitchAi.outcome("""{"id":1,"message_protocol":1}""", 1, "codex"))
    }
}
