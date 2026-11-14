package dev.multiprompt.companion.data

import dev.multiprompt.companion.account.AccountLayout
import dev.multiprompt.companion.account.AccountTab
import dev.multiprompt.companion.model.HostProfile
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
    fun botActivityAloneNeverChangesOrder() {
        val first = session.copy(name = "first", lastActivityEpochSeconds = 10)
        val second = session.copy(name = "second", lastActivityEpochSeconds = 20)
        val third = session.copy(name = "third", lastActivityEpochSeconds = 30)

        assertEquals(
            listOf(first, second, third),
            SessionSearch.newestFirst(listOf(first, second, third)),
        )
    }

    @Test
    fun botReplyDoesNotReorderUsedChats() {
        val older = session.copy(name = "older", lastActivityEpochSeconds = 100)
        val newer = session.copy(name = "newer", lastActivityEpochSeconds = 200)
        val used = mapOf(
            SessionReadStore.key("host-1", "older") to 500L,
            SessionReadStore.key("host-1", "newer") to 900L,
        )
        val before = SessionSearch.newestFirst(listOf(older, newer), used)

        val afterReply = SessionSearch.newestFirst(
            listOf(older.copy(lastActivityEpochSeconds = 5_000, outputActivityEpochSeconds = 5_000, replyAtEpochSeconds = 5_000, replyWaiting = true), newer),
            used,
        )

        assertEquals(listOf("newer", "older"), before.map { it.name })
        assertEquals(listOf("newer", "older"), afterReply.map { it.name })
    }

    @Test
    fun sendingAMessageMovesThatChatToTheTop() {
        val a = session.copy(name = "a", lastActivityEpochSeconds = 300)
        val b = session.copy(name = "b", lastActivityEpochSeconds = 100)
        val used = mapOf(
            SessionReadStore.key("host-1", "a") to 500L,
            SessionReadStore.key("host-1", "b") to 400L,
        )
        assertEquals(listOf("a", "b"), SessionSearch.newestFirst(listOf(a, b), used).map { it.name })

        val afterSend = used + (SessionReadStore.key("host-1", "b") to 600L)

        assertEquals(listOf("b", "a"), SessionSearch.newestFirst(listOf(a, b), afterSend).map { it.name })
    }

    @Test
    fun desktopUseFromAccountLayoutOrdersChatsInSeconds() {
        val host = HostProfile("host-1", "Hetzner", "vps", username = "valentin", keySecretId = "key")
        val mapping = mapOf(0 to host)
        val a = session.copy(name = "a")
        val b = session.copy(name = "b")
        val layout = AccountLayout(
            1,
            listOf("Other"),
            listOf(
                AccountTab("A", "a", 0, "Other", used = 1_000_000L),
                AccountTab("B", "b", 0, "Other", used = 2_000_000L),
            ),
        )

        assertEquals(listOf("b", "a"), SessionSearch.newestFirst(listOf(a, b), emptyMap(), layout, mapping).map { it.name })
        assertEquals(
            listOf("a", "b"),
            SessionSearch.newestFirst(listOf(a, b), mapOf(SessionReadStore.key("host-1", "a") to 3_000L), layout, mapping).map { it.name },
        )
    }

    @Test
    fun unusedChatsKeepDesktopTabOrderWhateverTheBotsDo() {
        val host = HostProfile("host-1", "Hetzner", "vps", username = "valentin", keySecretId = "key")
        val mapping = mapOf(0 to host)
        val layout = AccountLayout(
            1,
            listOf("Other"),
            listOf(AccountTab("A", "a", 0, "Other"), AccountTab("B", "b", 0, "Other")),
        )
        val quietA = session.copy(name = "a", lastActivityEpochSeconds = 10)
        val busyB = session.copy(name = "b", lastActivityEpochSeconds = 9_999)

        assertEquals(listOf("a", "b"), SessionSearch.newestFirst(listOf(busyB, quietA), emptyMap(), layout, mapping).map { it.name })
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
