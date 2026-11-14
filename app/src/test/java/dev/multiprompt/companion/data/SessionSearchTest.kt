package dev.multiprompt.companion.data

import dev.multiprompt.companion.model.TmuxSession
import org.junit.Assert.assertEquals
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

    @Test
    fun localInteractionMovesSessionAheadOfBackgroundPaneActivity() {
        val backgroundAgent = session.copy(name = "background", lastActivityEpochSeconds = 300)
        val workedHere = session.copy(name = "worked-here", lastActivityEpochSeconds = 100)

        assertEquals(
            listOf(workedHere, backgroundAgent),
            SessionSearch.newestFirst(
                listOf(backgroundAgent, workedHere),
                mapOf(SessionReadStore.key("host-1", "worked-here") to 400L),
            ),
        )
    }

    @Test
    fun tieOnRecencyPutsWaitingForReplyFirst() {
        val quiet = session.copy(name = "a", lastActivityEpochSeconds = 100, replyWaiting = false)
        val waiting = session.copy(name = "b", lastActivityEpochSeconds = 100, replyWaiting = true)

        assertEquals(listOf(waiting, quiet), SessionSearch.newestFirst(listOf(quiet, waiting)))
    }

    @Test
    fun tieOnRecencyAndAttentionPutsNewerPaneActivityFirst() {
        val viewedLater = session.copy(name = "a", lastActivityEpochSeconds = 100, replyWaiting = false)
        val activeLater = session.copy(name = "b", lastActivityEpochSeconds = 300, replyWaiting = false)
        val interaction = mapOf(
            SessionReadStore.key("host-1", "a") to 300L,
        )

        assertEquals(
            listOf(activeLater, viewedLater),
            SessionSearch.newestFirst(listOf(viewedLater, activeLater), interaction),
        )
    }

    @Test
    fun fullTieFallsBackToWorkspaceThenSessionId() {
        val base = session.copy(lastActivityEpochSeconds = 100, replyWaiting = false)
        val wsB = base.copy(name = "x", workspaceName = "b", hostSessionId = "1")
        val wsA2 = base.copy(name = "y", workspaceName = "a", hostSessionId = "10")
        val wsA1 = base.copy(name = "z", workspaceName = "a", hostSessionId = "9")

        assertEquals(
            listOf(wsA1, wsA2, wsB),
            SessionSearch.newestFirst(listOf(wsB, wsA2, wsA1)),
        )
    }

    @Test
    fun orderIsDeterministicForAnyInputOrder() {
        val base = session.copy(lastActivityEpochSeconds = 100, replyWaiting = false, workspaceName = "w")
        val a = base.copy(name = "a", hostSessionId = "1")
        val b = base.copy(name = "b", hostSessionId = "1")
        val c = base.copy(name = "c", hostSessionId = "2")
        val expected = listOf(a, b, c)

        assertEquals(expected, SessionSearch.newestFirst(listOf(c, b, a)))
        assertEquals(expected, SessionSearch.newestFirst(listOf(b, c, a)))
        assertEquals(expected, SessionSearch.newestFirst(listOf(a, c, b)))
    }
}
