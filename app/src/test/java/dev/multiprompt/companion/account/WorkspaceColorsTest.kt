package dev.multiprompt.companion.account

import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceColorsTest {
    private val base = AccountLayout(workspaces = listOf("Cedar", "Orbit", "Lagoon"), tabs = listOf(
        AccountTab("First", "bot-0", 0, "Cedar"), AccountTab("Second", "bot-1", 0, "Orbit"),
        AccountTab("Third", "bot-2", 0, "Lagoon"), AccountTab("Fourth", "bot-3", 0, "Cedar"),
    ))

    @Test fun optionalColorsRoundTripResetRenameAndRejectInvalidValues() {
        assertEquals(emptyMap<String, Int>(), AccountLayout.parse(base.json()).workspaceColors)
        val edited = base.setWorkspaceColor("Cedar", 6).renameWorkspace("Cedar", "Spruce")
        assertEquals(edited, AccountLayout.parse(JSONObject(edited.json().toString())))
        assertEquals(6, edited.workspaceColor("Spruce"))
        assertFalse(base.sameContent(base.setWorkspaceColor("Cedar", 6)))
        assertEquals(emptyMap<String, Int>(), edited.setWorkspaceColor("Spruce", null).workspaceColors)
        listOf(-1, 7, 1.5, "2", JSONObject.NULL).forEach { color ->
            val json = base.json().apply { getJSONArray("workspaces").getJSONObject(0).put("color", color) }
            assertThrows(Exception::class.java) { AccountLayout.parse(json) }
        }
    }

    @Test fun threeWayMergeKeepsRemoteColorsUnlessLocallyChangedAndHandlesResetAndOrdering() {
        val initial = base.setWorkspaceColor("Cedar", 1)
        val edited = initial.setWorkspaceColor("Cedar", null).setWorkspaceColor("Orbit", 4)
            .copy(workspaces = initial.workspaces.reversed())
        val remote = initial.setWorkspaceColor("Cedar", 5).setWorkspaceColor("Orbit", 6).setWorkspaceColor("Lagoon", 2).copy(revision = 2)
        val pending = PendingLayout.parse(JSONObject(PendingLayout(initial, edited).json().toString())).pull(remote)
        assertEquals(listOf("Lagoon", "Orbit", "Cedar"), pending.edited.workspaces)
        assertEquals(mapOf("Orbit" to 4, "Lagoon" to 2), pending.edited.workspaceColors)
        assertEquals(remote, PendingLayout(initial).pull(remote).edited)
        assertTrue(pending.dirty)
        val sent = pending.edited
        val later = sent.setWorkspaceColor("Lagoon", 3)
        assertEquals(3, PendingLayout(remote, later).acknowledge(sent, sent.copy(revision = 3)).edited.workspaceColor("Lagoon"))
    }

    @Test fun deletionDropsColorsAndConcurrentCreationKeepsExplicitPicks() {
        val deleted = base.copy(workspaces = listOf("Orbit", "Lagoon"), tabs = base.tabs.filterNot { it.workspace == "Cedar" })
        assertFalse(AccountLayout.rebase(base, base.setWorkspaceColor("Cedar", 6), deleted).workspaceColors.containsKey("Cedar"))
        val empty = AccountLayout()
        val automatic = AccountLayout(workspaces = listOf("Nova"))
        val picked = automatic.setWorkspaceColor("Nova", 4)
        assertEquals(4, AccountLayout.rebase(empty, automatic, picked).workspaceColor("Nova"))
        assertEquals(4, AccountLayout.rebase(empty, picked, automatic).workspaceColor("Nova"))
    }

    @Test fun inventoryOrderMissingSessionsAndReaderUseSharedAccountOrder() {
        val host = HostProfile("host", "Server", "example.com", username = "user", keySecretId = "key")
        val inventory = listOf(TmuxSession(host.id, "bot-2", 1, 0, 0), TmuxSession(host.id, "bot-0", 1, 0, 0))
        val layout = base.setWorkspaceColor("Orbit", 6)
        val rows = AccountHostMap.withAccountSessions(inventory, layout, mapOf(0 to host))
        rows.forEach { session ->
            val tab = AccountHostMap.tabFor(session, layout, mapOf(0 to host))!!
            assertEquals(layout.workspaceColor(tab.workspace), session.workspaceColor)
            assertEquals(session.workspaceColor, AccountHostMap.sessionFor(tab, inventory, mapOf(0 to host), layout)!!.workspaceColor)
        }
        assertEquals(listOf(2, 0, 6, 0), rows.map { it.workspaceColor })
    }
}
