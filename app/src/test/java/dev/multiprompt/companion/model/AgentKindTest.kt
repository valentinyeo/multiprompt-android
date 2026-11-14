package dev.multiprompt.companion.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentKindTest {
    @Test
    fun onlyLiveChatsAreMarkedLive() {
        fun session(name: String, command: String) = TmuxSession("h", name, 1, 0, 0, paneCommand = command)
        assertTrue(session("live-1d21840", "sleep").liveChat)
        assertFalse(session("live-1d21840", "bash").liveChat)
        assertFalse(session("cl-hypertasks", "claude").liveChat)
        assertFalse(session("work", "sleep").liveChat)
    }

    @Test
    fun paneCommandIsAuthoritativeForEverySupportedAgent() {
        assertEquals(AgentKind.CLAUDE, AgentKind.detect("work", paneCommand = "claude"))
        assertEquals(AgentKind.CODEX, AgentKind.detect("work", paneCommand = "/usr/bin/codex"))
        assertEquals(AgentKind.PI, AgentKind.detect("work", paneCommand = "pi"))
        assertEquals(AgentKind.KIMI, AgentKind.detect("work", paneCommand = "kimi-cli"))
        assertEquals(AgentKind.HAX, AgentKind.detect("work", paneCommand = "hax"))
    }

    @Test
    fun fallsBackToExplicitNamesAndStrongTuiSignatures() {
        assertEquals(AgentKind.CODEX, AgentKind.detect("cx-hypertasks"))
        assertEquals(AgentKind.CLAUDE, AgentKind.detect("cl-hypertasks"))
        assertEquals(AgentKind.PI, AgentKind.detect("hypertasks-pi-2"))
        assertEquals(AgentKind.KIMI, AgentKind.detect("kimi-hypertasks"))
        assertEquals(AgentKind.HAX, AgentKind.detect("hax-hypertasks"))
        assertEquals(AgentKind.CODEX, AgentKind.detect("work", preview = "OpenAI Codex"))
        assertEquals(AgentKind.HAX, AgentKind.detect("work", preview = "hax › codex · gpt-5.6-sol"))
    }

    @Test
    fun aDesktopLiveChatIsAClaudeChatEvenThoughItsTerminalOnlySleeps() {
        assertEquals(AgentKind.CLAUDE, AgentKind.detect("live-abc123", paneCommand = "sleep"))
        assertEquals(AgentKind.CLAUDE, AgentKind.detect("live-abc123", paneCommand = "/usr/bin/sleep"))
        assertEquals(AgentKind.OTHER, AgentKind.detect("sleeping-beauty", paneCommand = "sleep"))
        assertEquals(AgentKind.OTHER, AgentKind.detect("live-abc123", paneCommand = "bash"))
    }

    @Test
    fun incidentalAgentWordsDoNotMisclassifyShells() {
        assertEquals(AgentKind.OTHER, AgentKind.detect("api-server"))
        assertEquals(AgentKind.OTHER, AgentKind.detect("work", preview = "Ask codex to review this"))
    }

    @Test
    fun aDesktopChatShowsTheAiTheHostRegistryNamesForIt() {
        fun desktop(provider: String, command: String = "sleep") =
            TmuxSession("h", "live-0123456789ab4def81230123456789ab", 1, 0, 0, paneCommand = command, hostProvider = provider)
        assertEquals(AgentKind.CODEX, desktop("codex").agent)
        assertEquals(AgentKind.PI, desktop("pi").agent)
        assertEquals(AgentKind.HAX, desktop("hax").agent)
        assertEquals(AgentKind.CURSOR, desktop("cursor").agent)
        assertEquals(AgentKind.CLAUDE, desktop("claude").agent)
        assertEquals("Codex", desktop("codex").agent.label)
    }

    @Test
    fun aDesktopChatWithoutAnyRegistryAnswerStaysClaude() {
        fun desktop(provider: String) =
            TmuxSession("h", "live-0123456789ab4def81230123456789ab", 1, 0, 0, paneCommand = "sleep", hostProvider = provider)
        assertEquals(AgentKind.CLAUDE, desktop("").agent)
        assertEquals(AgentKind.CLAUDE, desktop("bogus").agent)
        assertEquals(AgentKind.CLAUDE, desktop("shell").agent)
    }

    @Test
    fun theRegistryNeverRelabelsATerminalSession() {
        val terminal = TmuxSession("h", "cl-work", 1, 0, 0, paneCommand = "claude", hostProvider = "codex")
        assertEquals(AgentKind.CLAUDE, terminal.agent)
        val shell = TmuxSession("h", "work", 1, 0, 0, paneCommand = "bash", hostProvider = "pi")
        assertEquals(AgentKind.OTHER, shell.agent)
    }

    @Test
    fun aPhoneChatKeepsItsNameTagWhenTheRegistryAgrees() {
        val name = AgentKind.liveChatName(AgentKind.PI, "api", "x1")
        assertEquals(AgentKind.PI, TmuxSession("h", name, 1, 0, 0, paneCommand = "sleep").agent)
        assertEquals(AgentKind.PI, TmuxSession("h", name, 1, 0, 0, paneCommand = "sleep", hostProvider = "pi").agent)
    }
}
