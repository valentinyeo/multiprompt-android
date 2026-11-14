package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.model.TmuxSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiStoppedTest {
    private val sid = "6f353a80-d3df-4e44-88aa-edcc6c2b8999"
    private val now = 1_000_000L

    private fun stopped(command: String, agent: AgentKind = AgentKind.CLAUDE, requestedAt: Long = 0) =
        AiStopped.stopped(agent, command, requestedAt, now)

    @Test fun bareShellsCountAsStopped() {
        listOf("bash", "-bash", "zsh", "/bin/zsh", "sh", "dash", "fish", "ksh", "BASH").forEach {
            assertTrue(it, stopped(it))
        }
    }

    @Test fun aRunningAiOrAnythingElseIsNotStopped() {
        listOf("claude", "node", "python3", "sleep", "", "vim", "bashful").forEach {
            assertFalse(it, stopped(it))
        }
    }

    @Test fun onlyClaudeChatsCanBeStopped() {
        listOf(AgentKind.CODEX, AgentKind.PI, AgentKind.KIMI, AgentKind.HAX, AgentKind.CURSOR, AgentKind.OTHER).forEach {
            assertFalse(it.name, stopped("bash", it))
        }
    }

    @Test fun aHostedLiveChatNeverLooksStopped() {
        val live = TmuxSession("h", "live-work-1", 1, 0, 0, paneCommand = "sleep")
        assertFalse(AiStopped.stopped(live.agent, live.paneCommand, 0, now))
    }

    @Test fun aNamedClaudeChatOnAShellIsStopped() {
        val session = TmuxSession("h", "claude-work", 1, 0, 0, paneCommand = "bash")
        assertEquals(AgentKind.CLAUDE, session.agent)
        assertTrue(AiStopped.stopped(session.agent, session.paneCommand, 0, now))
    }

    @Test fun restartGivesTheAiFortyFiveSecondsBeforeStoppedReturns() {
        assertFalse(stopped("bash", requestedAt = now - 1_000))
        assertFalse(stopped("bash", requestedAt = now - AiStopped.RESTART_GRACE_MILLIS + 1))
        assertTrue(stopped("bash", requestedAt = now - AiStopped.RESTART_GRACE_MILLIS))
        assertFalse(stopped("claude", requestedAt = now - AiStopped.RESTART_GRACE_MILLIS - 1))
    }

    @Test fun theRestartRequestIsTheAllowlistedDesktopShape() {
        val query = JSONObject(AiStopped.query(1, "claude-work", sid, "/home/me/app"))
        assertEquals(setOf("op", "id", "tmux", "sid", "cwd"), query.keys().asSequence().toSet())
        assertEquals("restart_ai", query.getString("op"))
        assertEquals(1, query.getInt("id"))
        assertEquals("claude-work", query.getString("tmux"))
        assertEquals(sid, query.getString("sid"))
        assertEquals("/home/me/app", query.getString("cwd"))
        assertTrue(AiStopped.query(1, "a", sid, "/x").endsWith("\n"))
    }

    @Test fun hostileNamesTravelOnlyAsJsonValues() {
        val hostile = "x'; rm -rf ~; echo '"
        val line = AiStopped.query(1, hostile, sid, hostile)
        assertEquals(1, line.count { it == '\n' })
        val query = JSONObject(line)
        assertEquals(hostile, query.getString("tmux"))
        assertEquals(hostile, query.getString("cwd"))
        assertEquals(setOf("op", "id", "tmux", "sid", "cwd"), query.keys().asSequence().toSet())
    }

    @Test fun anInvalidSessionIdFallsBackToContinue() {
        assertEquals(sid, AiStopped.restartSessionId(sid))
        assertEquals("", AiStopped.restartSessionId(""))
        assertEquals("", AiStopped.restartSessionId("abc"))
        assertEquals("", AiStopped.restartSessionId("$sid; reboot"))
        assertEquals("", JSONObject(AiStopped.query(1, "a", "x y z", "/x")).getString("sid"))
    }

    @Test fun theHostReplyMapsToAnOutcome() {
        val end = "@@MP_END@@\n"
        assertEquals(AiStopped.Outcome.Restarted, AiStopped.outcome("@mp\t1\trestart_ai\tok\t-\t0\n$end", 1))
        assertEquals(AiStopped.Outcome.AlreadyRunning, AiStopped.outcome("@mp\t1\trestart_ai\terror\tpane_busy\t0\n$end", 1))
        assertTrue(AiStopped.outcome("@mp\t1\trestart_ai\terror\tpane_input_failed\t0\n$end", 1) is AiStopped.Outcome.Failed)
        assertTrue(AiStopped.outcome("@mp\t1\trestart_ai\terror\tinvalid_session\t0\n$end", 1) is AiStopped.Outcome.Failed)
        assertTrue(AiStopped.outcome("@mp\t2\trestart_ai\tok\t-\t0\n$end", 1) is AiStopped.Outcome.Failed)
        assertTrue(AiStopped.outcome("@mp\t1\tsend\tok\t-\t0\n$end", 1) is AiStopped.Outcome.Failed)
        assertTrue(AiStopped.outcome("", 1) is AiStopped.Outcome.Failed)
    }
}
