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
}
