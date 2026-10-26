package dev.multiprompt.companion.account

import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSplitParityTest {
    private val host = HostProfile("v", "Hetzner", "vps", username = "valentin", keySecretId = "key")
    private val mapping = mapOf(0 to host)
    private val live = TmuxSession(host.id, "live-one", 2, 0, 5)
    private val liveTab = AccountTab("Live one", "live-one", 0, "Work", favorite = true)
    private val goneTab = AccountTab("Gone one", "gone-one", 0, "Work", favorite = true)
    private val unmappedTab = AccountTab("Elsewhere", "elsewhere", 1, "Work", favorite = true)
    private val layout = AccountLayout(1, listOf("Work"), listOf(liveTab, goneTab, unmappedTab))

    @Test fun everyMappedAccountTabIsListedEvenWithoutALiveSession() {
        val listed = AccountHostMap.withAccountSessions(listOf(live), layout, mapping)
        assertEquals(listOf("live-one", "gone-one"), listed.map { it.name })
        assertEquals(live, listed.first())
        assertEquals("Gone one", listed.last().accountName)
    }

    @Test fun accountFavoritesWithoutALiveSessionAppearInTheFavoritesSplit() {
        val listed = AccountHostMap.withAccountSessions(listOf(live), layout, mapping)
        assertEquals(listOf("live-one", "gone-one"), listed.filter { isFavoriteSession(it, layout, mapping) }.map { it.name })
    }

    @Test fun placeholdersJoinTheirAccountWorkspace() {
        val assignments = AccountHostMap.workspaceAssignments(listOf(live), layout, mapping)
        assertEquals("Work", assignments[SessionReadStore.key(host.id, "gone-one")])
        assertEquals("Work", assignments[SessionReadStore.key(host.id, "live-one")])
    }

    @Test fun liveCaseDifferenceDoesNotDuplicateTheRow() {
        val listed = AccountHostMap.withAccountSessions(listOf(live.copy(name = "LIVE-ONE")), layout, mapping)
        assertEquals(listOf("LIVE-ONE", "gone-one"), listed.map { it.name })
    }

    @Test fun withoutAnAccountLayoutTheInventoryIsUnchanged() {
        assertEquals(listOf(live), AccountHostMap.withAccountSessions(listOf(live), null, mapping))
        assertTrue(AccountHostMap.workspaceAssignments(listOf(live), null, mapping).isEmpty())
    }
}
