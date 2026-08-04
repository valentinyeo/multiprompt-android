package dev.multiprompt.companion.data

import dev.multiprompt.companion.model.TmuxSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSearchTest {
    private val session = TmuxSession(
        hostId = "host-1",
        name = "codex-hypertasks",
        windows = 1,
        attachedClients = 1,
        lastActivityEpochSeconds = 10,
        workingDirectory = "/home/valentin/projects/hypertasks",
        title = "HTPR-4920 updater",
        preview = "Finished the Android release workflow",
    )

    @Test
    fun matchesAcrossSessionWorkspaceHostAndPreviewFields() {
        assertTrue(SessionSearch.matches(session, "HTPR Codex"))
        assertTrue(SessionSearch.matches(session, "android", hostLabel = "Production VPS"))
        assertTrue(SessionSearch.matches(session, "production", hostLabel = "Production VPS"))
        assertTrue(SessionSearch.matches(session, "mobile", workspaceName = "Mobile Apps"))
    }

    @Test
    fun requiresEverySearchTermToMatch() {
        assertFalse(SessionSearch.matches(session, "HTPR missing"))
    }

    @Test
    fun blankQueryShowsEverySession() {
        assertTrue(SessionSearch.matches(session, "   "))
    }

    @Test
    fun newestActivitySortsFirst() {
        val old = session.copy(name = "old", lastActivityEpochSeconds = 10)
        val newest = session.copy(name = "new", lastActivityEpochSeconds = 30)
        val middle = session.copy(name = "middle", lastActivityEpochSeconds = 20)

        assertTrue(
            SessionSearch.newestFirst(listOf(old, newest, middle)) ==
                listOf(newest, middle, old),
        )
    }
}
