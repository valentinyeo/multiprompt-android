package dev.multiprompt.companion.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CreatedSessionTest {
    @Test
    fun plusLiveChatKeepsItsIdSoAnEmptyChatLoads() {
        val sid = "6b334002-4863-416d-a50c-c103ce6cb606"
        val session = createdSession("vps", CreatedSession("live-api-x1", sid), "/srv/api", 1)

        assertEquals(sid, session.hostSessionId)
        assertEquals(AgentKind.CLAUDE, session.agent)
    }

    @Test
    fun plainAgentSessionHasNoLiveId() {
        val session = createdSession("vps", CreatedSession("codex-api-x1"), "/srv/api", 1)

        assertEquals("", session.hostSessionId)
        assertEquals(AgentKind.CODEX, session.agent)
    }
}
