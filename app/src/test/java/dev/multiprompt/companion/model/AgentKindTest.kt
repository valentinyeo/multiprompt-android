package dev.multiprompt.companion.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentKindTest {
    @Test
    fun paneCommandIsAuthoritativeForEverySupportedAgent() {
        assertEquals(AgentKind.CLAUDE, AgentKind.detect("work", paneCommand = "claude"))
        assertEquals(AgentKind.CODEX, AgentKind.detect("work", paneCommand = "/usr/bin/codex"))
        assertEquals(AgentKind.PI, AgentKind.detect("work", paneCommand = "pi"))
        assertEquals(AgentKind.KIMI, AgentKind.detect("work", paneCommand = "kimi-cli"))
    }

    @Test
    fun fallsBackToExplicitNamesAndStrongTuiSignatures() {
        assertEquals(AgentKind.CODEX, AgentKind.detect("cx-hypertasks"))
        assertEquals(AgentKind.CLAUDE, AgentKind.detect("cl-hypertasks"))
        assertEquals(AgentKind.PI, AgentKind.detect("hypertasks-pi-2"))
        assertEquals(AgentKind.KIMI, AgentKind.detect("kimi-hypertasks"))
        assertEquals(AgentKind.CODEX, AgentKind.detect("work", preview = "OpenAI Codex"))
    }

    @Test
    fun incidentalAgentWordsDoNotMisclassifyShells() {
        assertEquals(AgentKind.OTHER, AgentKind.detect("api-server"))
        assertEquals(AgentKind.OTHER, AgentKind.detect("work", preview = "Ask codex to review this"))
    }
}
