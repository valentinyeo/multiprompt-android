package dev.multiprompt.companion.account

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountLayoutTest {
    private val one = AccountTab("One", "one", 0, "Other")
    private val two = AccountTab("Two", "two", 1, "Other")
    private val base = AccountLayout(1, listOf("Other", "Product"), listOf(one, two))

    @Test fun desktopFixturePreservesNamesOrderHostsAndLegacyManual() {
        val fixture = JSONObject("""{"schema":1,"revision":7,"workspaces":[{"name":"inne"},{"name":"Kraków 🚀"},{"name":"Other"}],"tabs":[{"kind":"tmux","name":"Inne shell","tmux_session":"inne-ssh","tmux_host":0,"workspace":"inne"},{"kind":"tmux","name":"CRO","manual":true,"tmux_session":"cro","tmux_host":2,"workspace":"Kraków 🚀"}]}""")
        val layout = AccountLayout.parse(fixture)
        assertEquals(listOf("inne", "Kraków 🚀", "Other"), layout.workspaces)
        assertEquals(listOf("inne-ssh", "cro"), layout.tabs.map { it.session })
        assertEquals(listOf(0, 2), layout.tabs.map { it.host })
        assertFalse(layout.tabs[0].manual)
        assertTrue(layout.tabs[1].manual)
        assertEquals(layout, AccountLayout.parse(layout.json()))
        assertEquals(layout, AccountLayout.response(JSONObject().put("revision", 7).put("layout", fixture)))
        assertThrows(IllegalArgumentException::class.java) { AccountLayout.response(JSONObject().put("revision", 8).put("layout", fixture)) }
    }

    @Test fun invalidLayoutsFailClosed() {
        val invalid = listOf(
            base.json().put("schema", 2), base.json().put("secret", "not allowed"), base.json().put("revision", 1.5),
            base.json().put("revision", -1), base.json().apply { getJSONArray("tabs").getJSONObject(0).put("tmux_host", "0") },
            base.json().apply { getJSONArray("tabs").getJSONObject(0).put("manual", "true") },
            base.json().apply { getJSONArray("tabs").getJSONObject(0).put("tmux_session", "one;kill") },
            base.json().apply { getJSONArray("tabs").getJSONObject(0).put("workspace", "Missing") },
            base.json().apply { getJSONArray("tabs").put(one.copy(session = "ONE").json()) },
        )
        invalid.forEach { assertThrows(Exception::class.java) { AccountLayout.parse(it) } }
        assertThrows(IllegalArgumentException::class.java) { base.copy(workspaces = listOf("é".repeat(16))).validate() }
        assertThrows(IllegalArgumentException::class.java) { base.copy(workspaces = listOf("Other", "Other")).validate() }
        assertThrows(IllegalArgumentException::class.java) { base.renameTab(one.identity, "bad\nname") }
        assertThrows(IllegalArgumentException::class.java) { base.renameTab(one.identity, "\uD800") }
        assertThrows(IllegalArgumentException::class.java) { base.copy(workspaces = (0..9).map(Int::toString)).validate() }
    }

    @Test fun editsChangeOnlyPortableMetadata() {
        val layout = base.renameTab(one.identity, "Manual").moveTab(one.identity, "Product")
            .addTab(AccountTab("Three", "three", 2, "Other")).closeTab(two.identity)
            .renameWorkspace("Product", "Renamed workspace")
        assertEquals(listOf("Other", "Renamed workspace"), layout.workspaces)
        assertEquals(listOf("one", "three"), layout.tabs.map { it.session })
        assertEquals("Manual", layout.tabs[0].name)
        assertTrue(layout.tabs[0].manual)
        assertEquals("Renamed workspace", layout.tabs[0].workspace)
        assertEquals(0, layout.tabs[0].host)
        assertEquals("One", base.tabs[0].name)
    }

    @Test fun applyUsesServerNamesOrderAndMembershipWithoutWritingUnchangedCache() {
        val remote = base.copy(revision = 8, workspaces = base.workspaces.reversed(), tabs = listOf(two, one.copy(name = "Remote", workspace = "Product")))
        val applied = PendingLayout(base).pull(remote)
        assertEquals(remote, applied.edited)
        assertFalse(applied.dirty)
        assertTrue(base.sameContent(base.copy(revision = 99)))
    }

    @Test fun conflictReplaysOnlyChangedFieldsAndPreservesRemoteAdditionsAndMoves() {
        val edited = base.renameTab(one.identity, "Local")
        val remote = base.copy(revision = 7, tabs = listOf(one.copy(workspace = "Product"), two.copy(name = "Remote two"), AccountTab("Three", "three", 2, "Product")))
        val result = PendingLayout(base, edited).pull(remote)
        assertEquals(7, result.base.revision)
        assertEquals("Local", result.edited.tabs[0].name)
        assertEquals("Product", result.edited.tabs[0].workspace)
        assertEquals("Remote two", result.edited.tabs[1].name)
        assertEquals(3, result.edited.tabs.size)
        assertTrue(result.dirty)
    }

    @Test fun staleRenameCannotResurrectDeletionAndLocalCloseSurvives409() {
        val remote = base.copy(revision = 3, tabs = listOf(two))
        assertEquals(remote, PendingLayout(base, base.renameTab(one.identity, "Stale")).pull(remote).edited)
        val closed = PendingLayout(base, base.closeTab(one.identity)).pull(base.copy(revision = 2))
        assertEquals(listOf(two), closed.edited.tabs)
    }

    @Test fun concurrentWorkspaceAndTabOrderingSurvivesAndPendingEditsRoundTrip() {
        val added = AccountTab("Three", "three", 2, "Product")
        val edited = base.copy(workspaces = base.workspaces.reversed(), tabs = listOf(two, added, one))
        val remoteAdded = AccountTab("Four", "four", 0, "Remote")
        val remote = base.copy(revision = 9, workspaces = base.workspaces + "Remote", tabs = base.tabs + remoteAdded)
        val state = PendingLayout.parse(JSONObject(PendingLayout(base, edited).json().toString())).pull(remote)
        assertEquals(listOf("Product", "Other", "Remote"), state.edited.workspaces)
        assertEquals(listOf(two, added, one, remoteAdded), state.edited.tabs)
    }

    @Test fun editsDuringPutRebaseAgainstAcknowledgement() {
        val sent = base.renameTab(one.identity, "Uploaded")
        val later = sent.moveTab(one.identity, "Product").closeTab(two.identity)
        val server = sent.copy(revision = 2)
        val state = PendingLayout(base, later).acknowledge(sent, server)
        assertEquals(server, state.base)
        assertEquals("Uploaded", state.edited.tabs.single().name)
        assertEquals("Product", state.edited.tabs.single().workspace)
        assertTrue(state.dirty)
        val acknowledged = state.acknowledge(state.edited, state.edited.copy(revision = 3))
        assertFalse(acknowledged.dirty)
    }

    @Test fun emptyAccountDoesNotSilentlyImportOrOverwriteLegacyFolders() {
        val empty = AccountLayout(4)
        assertEquals(empty, PendingLayout(empty).pull(empty).edited)
        assertFalse(PendingLayout(empty).dirty)
    }

    @Test fun conflictMatchesDesktopCaseInsensitiveIdentityAndKeepsManualFlag() {
        val remote = base.copy(revision = 4, tabs = listOf(one.copy(session = "ONE"), two))
        val result = AccountLayout.rebase(base, base.renameTab(one.identity, "One"), remote)
        assertEquals(2, result.tabs.size)
        assertEquals("ONE", result.tabs[0].session)
        assertTrue(result.tabs[0].manual)
    }
}
