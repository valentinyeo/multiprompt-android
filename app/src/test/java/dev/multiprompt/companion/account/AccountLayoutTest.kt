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

    @Test fun botAppearanceRoundTripsAndReplaysOnlyItsOwnChange() {
        val picked = base.setAppearance(one.identity, 7, 2)
        val parsed = AccountLayout.parse(org.json.JSONObject(picked.json().toString()))
        assertEquals(7, parsed.tabs.first { it.identity == one.identity }.face)
        assertEquals(2, parsed.tabs.first { it.identity == one.identity }.color)
        val remote = picked.copy(revision = 4).setAppearance(two.identity, 3, 5)
        val rebased = AccountLayout.rebase(picked, picked, remote)
        assertEquals(7, rebased.tabs.first { it.identity == one.identity }.face)
        assertEquals(3, rebased.tabs.first { it.identity == two.identity }.face)
    }

    @Test fun pickedWorkspaceColorWinsAndAutoRestoresThePaletteOrder() {
        val picked = base.setWorkspaceColor("Product", 5)
        assertEquals(5, picked.workspaceColor("Product"))
        assertEquals(0, picked.workspaceColor("Other"))
        assertEquals(1, picked.setWorkspaceColor("Product", null).workspaceColor("Product"))
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

    @Test fun phoneCloseDropsOnlyThatTabAndNeverWipesUnknownTabsOrWorkspaces() {
        val inne = AccountTab("Inne", "inne", 0, "inne")
        val remote = AccountLayout(9, listOf("Other", "Product", "inne"), listOf(one, two, inne))
        val sent = PendingLayout(base, base.closeTab(one.identity)).pull(remote).edited
        assertEquals(listOf("two", "inne"), sent.tabs.map { it.session })
        assertEquals(remote.workspaces, sent.workspaces)
        val renamed = PendingLayout(base, base.renameWorkspace("Product", "P2")).pull(remote).edited
        assertEquals(listOf("one", "two", "inne"), renamed.tabs.map { it.session })
        assertTrue("inne" in renamed.workspaces)
        assertFalse(PendingLayout(base).pull(remote).dirty)
        assertEquals(remote.tabs, PendingLayout(AccountLayout()).pull(remote).edited.tabs)
    }
    @Test fun visitsRoundTripPreservesAbsentEmptyAndSafeIntegerValues() {
        for (visits in listOf(null, emptyList(), listOf(0L, 1_800_000_000_000L, 9_007_199_254_740_991L))) {
            val layout = base.copy(tabs = listOf(one.copy(visits = visits), two))
            val json = JSONObject(layout.json().toString())
            assertEquals(visits != null, json.getJSONArray("tabs").getJSONObject(0).has("visits"))
            assertEquals(layout, AccountLayout.parse(json))
            assertFalse(json.getJSONArray("tabs").getJSONObject(1).has("visits"))
        }
    }

    @Test fun visitsRejectNonArraysInvalidNumbersDuplicatesDisorderAndOverCapacity() {
        val invalid = listOf(-1, true, "123", JSONObject.NULL, JSONObject(),
            org.json.JSONArray(listOf(-1)), org.json.JSONArray(listOf(0.5)),
            org.json.JSONArray(listOf(java.math.BigDecimal("9007199254740990.5"))),
            org.json.JSONArray(listOf(9_007_199_254_740_992L)), org.json.JSONArray(listOf(Long.MAX_VALUE)),
            org.json.JSONArray(listOf("123")), org.json.JSONArray(listOf(true)),
            org.json.JSONArray(listOf(JSONObject.NULL)), org.json.JSONArray(listOf(JSONObject())),
            org.json.JSONArray(listOf(1, 1)), org.json.JSONArray(listOf(2, 1)), org.json.JSONArray((0..8).toList()))
        invalid.forEach { visits ->
            val json = base.json()
            json.getJSONArray("tabs").getJSONObject(0).put("visits", visits)
            assertThrows("visits=$visits", IllegalArgumentException::class.java) { AccountLayout.parse(json) }
        }
        for (visits in listOf(listOf(-1L), listOf(9_007_199_254_740_992L), listOf(1L, 1L), listOf(2L, 1L), (0L..8L).toList())) {
            assertThrows(IllegalArgumentException::class.java) { base.copy(tabs = listOf(one.copy(visits = visits))).validate() }
        }
    }

    @Test fun visitsMergeUnionDedupeSortCapAndNeverDropOnOmission() {
        val original = base.copy(tabs = listOf(one.copy(visits = listOf(1L, 2L, 3L)), two))
        val edited = original.copy(tabs = listOf(one.copy(visits = listOf(3L, 5L, 7L, 9L, 11L)), two))
        val remote = original.copy(revision = 2, tabs = listOf(one.copy(visits = listOf(2L, 4L, 6L, 8L, 10L)), two))
        val result = PendingLayout(original, edited).pull(remote)
        assertEquals((4L..11L).toList(), result.edited.tabs[0].visits)
        assertNull(result.edited.tabs[1].visits)
        assertEquals(result, PendingLayout.parse(JSONObject(result.json().toString())))
        assertEquals(original.tabs, AccountLayout.rebase(original, base, base).tabs)
        val empty = base.copy(tabs = emptyList())
        assertEquals((3L..10L).toList(), AccountLayout.rebase(empty,
            base.copy(tabs = listOf(one.copy(visits = (1L..5L).toList()))),
            base.copy(tabs = listOf(one.copy(visits = (5L..10L).toList())))).tabs.single().visits)
    }

    @Test fun visitDuringPutSurvivesAcknowledgementAndLocalRecordingCapsWithoutTouchingOtherFields() {
        val sent = base.recordVisit(one.identity, 100)
        val later = sent.recordVisit(one.identity, 200)
        val remote = sent.recordVisit(one.identity, 300).copy(revision = 2)
        val pending = PendingLayout(base, later).acknowledge(sent, remote)
        assertEquals(listOf(100L, 200L, 300L), pending.edited.tabs[0].visits)
        assertTrue(pending.dirty)
        assertFalse(pending.acknowledge(pending.edited, pending.edited.copy(revision = 3)).dirty)
        val capped = (0L..10L).fold(base) { layout, visit -> layout.recordVisit(one.identity, visit) }
        assertEquals((3L..10L).toList(), capped.tabs[0].visits)
        assertEquals(capped, capped.recordVisit(one.identity, 9))
        assertEquals(base.tabs[1], capped.tabs[1])
        assertEquals(base, capped.copy(tabs = listOf(capped.tabs[0].copy(visits = null), capped.tabs[1])))
        assertThrows(IllegalArgumentException::class.java) { capped.recordVisit(one.identity, -1) }
    }


    private fun mutedLayout(muted: Boolean?, name: String = "One", revision: Int = 1): JSONObject {
        val json = JSONObject("""{"schema":1,"revision":$revision,"workspaces":[{"name":"Other"}],"tabs":[{"kind":"tmux","name":"$name","tmux_session":"one","tmux_host":0,"workspace":"Other"}]}""")
        muted?.let { json.getJSONArray("tabs").getJSONObject(0).put("muted", it) }
        return json
    }

    @Test fun mutedRoundTripsAndAbsentStaysAbsent() {
        for (muted in listOf(true, false)) {
            val parsed = AccountLayout.parse(mutedLayout(muted))
            assertEquals(muted, parsed.json().getJSONArray("tabs").getJSONObject(0).get("muted"))
            assertEquals(parsed, AccountLayout.parse(JSONObject(parsed.json().toString())))
        }
        assertFalse(AccountLayout.parse(mutedLayout(null)).json().getJSONArray("tabs").getJSONObject(0).has("muted"))
    }

    @Test fun mutedMustBeBoolean() {
        val json = mutedLayout(null)
        json.getJSONArray("tabs").getJSONObject(0).put("muted", "yes")
        assertThrows(IllegalArgumentException::class.java) { AccountLayout.parse(json) }
    }

    @Test fun remoteMutedSurvivesPullWhenPhoneOnlyRenamed() {
        val base = AccountLayout.parse(mutedLayout(null))
        val edited = AccountLayout.parse(mutedLayout(null, "Phone rename"))
        val remote = AccountLayout.parse(mutedLayout(true, revision = 2))
        val tab = PendingLayout(base, edited).pull(remote).edited.json().getJSONArray("tabs").getJSONObject(0)
        assertEquals("Phone rename", tab.getString("name"))
        assertEquals(true, tab.get("muted"))
    }

    @Test fun mutedSurvivesAcknowledgeOfAPhoneEdit() {
        val base = AccountLayout.parse(mutedLayout(true))
        val edited = AccountLayout.parse(mutedLayout(true, "Phone rename"))
        val remote = AccountLayout.parse(mutedLayout(true, "Phone rename", 2))
        val acknowledged = PendingLayout(base, edited).acknowledge(edited, remote)
        assertEquals(true, acknowledged.edited.json().getJSONArray("tabs").getJSONObject(0).get("muted"))
        assertEquals(true, acknowledged.base.json().getJSONArray("tabs").getJSONObject(0).get("muted"))
    }
}
