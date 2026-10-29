package dev.multiprompt.companion.account

import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import org.junit.Assert.*
import org.junit.Test

class AccountHostMapTest {
    private val valentin = HostProfile("v", "Hetzner", "46.224.218.39", username = "valentin", keySecretId = "v-key")
    private val inne = valentin.copy(id = "i", label = "Hetzner (inne)", username = "inne", keySecretId = "i-key")
    private val agent = valentin.copy(id = "a", label = "Hetzner (agent)", username = "agent", keySecretId = "a-key")
    private val contabo = valentin.copy(id = "c", label = "Contabo UK", hostname = "contabo-uk")
    private val cro = valentin.copy(id = "r", label = "Inne CRO", hostname = "inne-cro-vps")
    private val hosts = listOf(agent, inne, valentin, contabo, cro)
    private val tab = AccountTab("✳ Exact desktop name", "inne-shell", 0, "inne")
    private val layout = AccountLayout(1, listOf("inne"), listOf(tab))

    @Test fun hostSlotsUseExistingProfilesWithoutChangingUserCredentialsOrWrappingAttach() {
        val mapping = AccountHostMap.resolve(hosts, emptyMap())
        assertEquals(valentin, mapping[0]); assertEquals(contabo, mapping[1]); assertEquals(cro, mapping[2])
        assertEquals("v-key", mapping[0]!!.keySecretId)
        val live = TmuxSession(valentin.id, "inne-shell", 2, 3, 4, workingDirectory = "/home/inne/projects", title = "Different", windowName = "bash")
        val opened = AccountHostMap.sessionFor(tab, listOf(live), mapping)!!
        assertEquals("v", opened.hostId); assertEquals("inne-shell", opened.name)
        assertEquals(live.workingDirectory, opened.workingDirectory)
        assertEquals(tab.name, opened.displayName)
        assertEquals(tab, AccountHostMap.tabFor(live.copy(name = "INNE-SHELL"), layout, mapping))
        assertNull(AccountHostMap.tabFor(live.copy(hostId = inne.id), layout, mapping))
        assertNull(AccountHostMap.tabFor(live.copy(hostId = agent.id), layout, mapping))
        assertEquals("inne", hosts[1].username); assertEquals("agent", hosts[0].username)
    }

    @Test fun workspaceMetadataFollowsAccountOwnershipForLiveOfflineAndMovedSessions() {
        val mapping = AccountHostMap.resolve(hosts, emptyMap())
        val live = TmuxSession(valentin.id, "inne-shell", 1, 0, 0)
        assertEquals("inne", AccountHostMap.withAccountSessions(listOf(live), layout, mapping).single().workspaceName)
        assertEquals("inne", AccountHostMap.sessionFor(tab, emptyList(), mapping)!!.workspaceName)
        val moved = layout.copy(workspaces = listOf("inne", "Software Factory"), tabs = listOf(tab.copy(workspace = "Software Factory")))
        assertEquals("Software Factory", AccountHostMap.withAccountSessions(listOf(live), moved, mapping).single().workspaceName)
        assertEquals("", AccountHostMap.withAccountSessions(listOf(live.copy(name = "unassigned")), layout, mapping).first().workspaceName)
    }

    @Test fun missingOrAmbiguousHostsStayUnmappedUntilExplicitChoice() {
        assertNull(AccountHostMap.resolve(listOf(inne, agent), emptyMap())[0])
        val duplicate = valentin.copy(id = "duplicate")
        assertNull(AccountHostMap.resolve(hosts + duplicate, emptyMap())[0])
        assertEquals(duplicate, AccountHostMap.resolve(hosts + duplicate, mapOf(0 to "duplicate"))[0])
        assertNull(AccountHostMap.resolve(hosts, mapOf(0 to "deleted"))[0])
        assertNull(AccountHostMap.sessionFor(tab, emptyList(), emptyMap()))
    }

    @Test fun explicitOverridesRejectOtherUsersAtSlotZeroAndNeverReuseProfiles() {
        assertNull(AccountHostMap.resolve(hosts, mapOf(0 to inne.id))[0])
        assertNull(AccountHostMap.resolve(hosts, mapOf(0 to agent.id))[0])
        assertEquals(mapOf(0 to valentin), AccountHostMap.resolve(listOf(valentin), mapOf(0 to valentin.id, 1 to valentin.id)))
        assertEquals(mapOf(1 to contabo), AccountHostMap.resolve(listOf(contabo), mapOf(1 to contabo.id, 2 to contabo.id)))
    }

    @Test fun offlineAccountTabsRemainOpenableAndMembershipNeverComesFromCwd() {
        val mapping = AccountHostMap.resolve(hosts, emptyMap())
        val session = AccountHostMap.sessionFor(tab, emptyList(), mapping)!!
        assertEquals("v", session.hostId); assertEquals("inne-shell", session.name); assertEquals(tab.name, session.displayName)
        assertNull(AccountHostMap.tabFor(session.copy(name = "not-in-layout", workingDirectory = "/home/inne/projects"), layout, mapping))
    }
}
