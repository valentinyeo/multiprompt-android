package dev.multiprompt.companion.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatIdTest {
    private val base = TmuxSession(hostId = "h", name = "live-app-1", windows = 1, attachedClients = 0, lastActivityEpochSeconds = 0)

    @Test fun hostedChatCopiesItsHostSessionIdLikeDesktop() {
        val sid = "6f353a80-d3df-4e44-88aa-edcc6c2b8999"
        assertEquals(sid, ChatId.of(base.copy(hostSessionId = sid)))
    }

    @Test fun chatWithoutHostSessionIdCopiesTheTmuxNameLikeDesktop() {
        assertEquals("work-1", ChatId.of(base.copy(name = "work-1")))
    }

    @Test fun noIdAtAllHidesTheRow() {
        assertNull(ChatId.of(base.copy(name = "")))
    }
}
