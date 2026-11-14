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

    @Test fun desktopPictureNumberIsReadAndAlwaysComesFromTheServer() {
        val json = JSONObject("""{"schema":1,"revision":5,"workspaces":[{"name":"Other"}],"tabs":[{"kind":"tmux","name":"One","tmux_session":"one","tmux_host":0,"workspace":"Other","photo":3}]}""")
        val pulled = AccountLayout.parse(json)
        assertEquals(3L, pulled.tabs[0].photo)
        assertEquals(pulled, AccountLayout.parse(JSONObject(pulled.json().toString())))
        listOf<Any>(0, -1, 1.5, "3", true).forEach { bad ->
            assertThrows(Exception::class.java) { AccountLayout.parse(JSONObject(json.toString()).apply { getJSONArray("tabs").getJSONObject(0).put("photo", bad) }) }
        }
        val local = pulled.renameTab(one.identity, "Renamed")
        val newer = pulled.copy(revision = 6, tabs = pulled.tabs.map { it.copy(photo = 4) })
        assertEquals(4L, AccountLayout.rebase(pulled, local, newer).tabs[0].photo)
        assertEquals("Renamed", AccountLayout.rebase(pulled, local, newer).tabs[0].name)
        assertNull(AccountLayout.rebase(pulled, local, newer.copy(tabs = newer.tabs.map { it.copy(photo = null) })).tabs[0].photo)
        assertNull(AccountLayout.rebase(newer, newer.copy(tabs = newer.tabs.map { it.copy(photo = 9) }), pulled.copy(tabs = pulled.tabs.map { it.copy(photo = null) })).tabs[0].photo)
    }

    @Test fun retiredColourNumbersFallBackToAutoWithoutBreakingThePhone() {
        val json = org.json.JSONObject("""{"schema":1,"revision":5,"workspaces":[{"name":"Other","color":9}],"tabs":[{"kind":"tmux","name":"One","tmux_session":"one","tmux_host":0,"workspace":"Other","color":10}]}""")
        val pulled = AccountLayout.parse(json)
        assertEquals(0, pulled.workspaceColor("Other"))
        assertNull(pulled.tabs.first { it.identity == one.identity }.color)
        val written = pulled.json()
        assertFalse(written.getJSONArray("workspaces").getJSONObject(0).has("color"))
        assertFalse(written.getJSONArray("tabs").getJSONObject(0).has("color"))
    }

    @Test fun whiteRoundTripsLikeEveryOtherSoftColour() {
        val json = org.json.JSONObject("""{"schema":1,"revision":5,"workspaces":[{"name":"Other","color":7}],"tabs":[{"kind":"tmux","name":"One","tmux_session":"one","tmux_host":0,"workspace":"Other","color":7}]}""")
        val again = AccountLayout.parse(org.json.JSONObject(AccountLayout.parse(json).json().toString()))
        assertEquals(7, again.workspaceColor("Other"))
        assertEquals(7, again.tabs.first { it.identity == one.identity }.color)
    }

    @Test fun desktopHostRemovalsAreReadAndSurviveAPhoneEdit() {
        val json = org.json.JSONObject("""{"schema":1,"revision":3,"workspaces":[{"name":"Other"}],"tabs":[{"kind":"tmux","name":"One","tmux_session":"one","tmux_host":0,"workspace":"Other"}],"removed_hosts":[2]}""")
        val pulled = AccountLayout.parse(json)
        assertEquals(listOf(2), pulled.removedHosts)
        val edited = pulled.setWorkspaceColor("Other", 3)
        assertEquals(listOf(2), edited.removedHosts)
        val written = edited.json()
        assertEquals(1, written.getJSONArray("removed_hosts").length())
        val rebased = AccountLayout.rebase(pulled, edited, pulled.copy(revision = 4))
        assertEquals(listOf(2), rebased.removedHosts)
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

    @Test fun desktopGroupNameIsReadAndKeptWhenThePhoneSaves() {
        val json = JSONObject("""{"schema":1,"revision":5,"workspaces":[{"name":"Other"}],"tabs":[{"kind":"tmux","name":"One","tmux_session":"one","tmux_host":0,"workspace":"Other","group":"Runners"},{"kind":"tmux","name":"Two","tmux_session":"two","tmux_host":1,"workspace":"Other","group":""}]}""")
        val pulled = AccountLayout.parse(json)
        assertEquals(listOf("Runners", ""), pulled.tabs.map { it.group })
        assertEquals("Runners", pulled.json().getJSONArray("tabs").getJSONObject(0).getString("group"))
        assertEquals(pulled, AccountLayout.parse(JSONObject(pulled.json().toString())))
        listOf<Any>(1, true, "a".repeat(65), "bad\nname", JSONObject.NULL).forEach { bad ->
            assertThrows(Exception::class.java) { AccountLayout.parse(JSONObject(json.toString()).apply { getJSONArray("tabs").getJSONObject(0).put("group", bad) }) }
        }
        val local = pulled.renameTab(one.identity, "Renamed")
        val newer = pulled.copy(revision = 6, tabs = pulled.tabs.map { it.copy(group = "Leads") })
        val saved = AccountLayout.rebase(pulled, local, newer)
        assertEquals(listOf("Leads", "Leads"), saved.tabs.map { it.group })
        assertEquals("Renamed", saved.tabs[0].name)
        assertNull(AccountTab("One", "one", 0, "Other").json().opt("group"))
    }

    @Test fun renameGroupRewritesEveryChatInThatGroupAndSyncsLikeAnyGroupEdit() {
        val three = AccountTab("Three", "three", 0, "Other")
        val grouped = base.copy(tabs = listOf(one.copy(group = "Runners"), two.copy(group = "Runners"), three.copy(group = "Design"), AccountTab("Four", "four", 0, "Other")))
        val renamed = grouped.renameGroup("Runners", "Builders")
        assertEquals(listOf("Builders", "Builders", "Design", null), renamed.tabs.map { it.group })
        assertEquals(grouped.tabs.map { it.name }, renamed.tabs.map { it.name })
        assertEquals("Builders", renamed.json().getJSONArray("tabs").getJSONObject(1).getString("group"))
        assertEquals(grouped, grouped.renameGroup("Runners", "Runners"))
        val remote = grouped.copy(revision = 2, tabs = grouped.tabs.map { if (it.session == "three") it.copy(favorite = true) else it })
        val saved = AccountLayout.rebase(grouped, renamed, remote)
        assertEquals(listOf("Builders", "Builders", "Design", null), saved.tabs.map { it.group })
        assertEquals(true, saved.tabs[2].favorite)
    }

    @Test fun renameGroupKeepsItsPositionInGroupOrder() {
        val grouped = base.copy(
            tabs = listOf(one.copy(group = "Runners"), two.copy(group = "Design")),
            groupOrder = listOf("Hypertask", "Runners", "Design"),
        )
        val renamed = grouped.renameGroup("Runners", "Builders")
        assertEquals(listOf("Hypertask", "Builders", "Design"), renamed.groupOrder)
        assertEquals(listOf("Builders", "Design"), renamed.tabs.map { it.group })
        val sent = renamed.json().getJSONArray("group_order")
        assertEquals(listOf("Hypertask", "Builders", "Design"), (0 until sent.length()).map { sent.getString(it) })
        assertNull(base.copy(tabs = listOf(one.copy(group = "Runners"))).renameGroup("Runners", "Builders").groupOrder)
    }

    @Test fun renameGroupRewritesFavoritesGroupsAndKeepsEmptyOnes() {
        val grouped = base.copy(
            tabs = listOf(
                one.copy(group = "Runners", starredGroup = "Hypertask"),
                two.copy(group = "Design", starredGroup = ""),
                AccountTab("Three", "three", 0, "Other", starredGroup = "Hypertask"),
                AccountTab("Four", "four", 0, "Other", starredGroup = "Other group"),
            ),
            groupOrder = listOf("Hypertask", "Runners"),
        )
        val renamed = grouped.renameGroup("Hypertask", "Product")
        assertEquals(listOf("Product", "", "Product", "Other group"), renamed.tabs.map { it.starredGroup })
        assertEquals(listOf("Runners", "Design", null, null), renamed.tabs.map { it.group })
        assertEquals(listOf("Product", "Runners"), renamed.groupOrder)
        assertEquals("Product", renamed.json().getJSONArray("tabs").getJSONObject(0).getString("starred_group"))
        assertEquals("", renamed.json().getJSONArray("tabs").getJSONObject(1).getString("starred_group"))
        val reparsed = AccountLayout.parse(JSONObject(renamed.json().toString()))
        assertEquals(renamed.tabs.map { it.starredGroup }, reparsed.tabs.map { it.starredGroup })
        assertEquals(renamed.groupOrder, reparsed.groupOrder)
    }

    @Test fun renameGroupIntoANameAlreadyInGroupOrderMergesWithoutDuplicates() {
        val grouped = base.copy(
            tabs = listOf(one.copy(group = "Runners"), two.copy(group = "Design")),
            groupOrder = listOf("Runners", "Design", "Builders"),
        )
        val renamed = grouped.renameGroup("Runners", "Builders")
        assertEquals(listOf("Builders", "Design"), renamed.groupOrder)
        assertEquals(listOf("Builders", "Design"), renamed.tabs.map { it.group })
        val later = base.copy(tabs = listOf(one.copy(group = "Runners")), groupOrder = listOf("Builders", "Runners"))
        assertEquals(listOf("Builders"), later.renameGroup("Runners", "Builders").groupOrder)
    }

    @Test fun renameGroupRejectsANameTakenByAFavoritesGroup() {
        val grouped = base.copy(tabs = listOf(one.copy(group = "Runners"), two.copy(starredGroup = "Hypertask")))
        assertThrows(IllegalArgumentException::class.java) { grouped.renameGroup("Runners", "Hypertask") }
    }

    @Test fun renameGroupRejectsBlankLongControlAndTakenNames() {
        val grouped = base.copy(tabs = listOf(one.copy(group = "Runners"), two.copy(group = "Design")))
        listOf("", "   ", "a".repeat(65), "bad\nname", "Design").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { grouped.renameGroup("Runners", bad) }
        }
        assertEquals(listOf("Runners", "Design"), grouped.tabs.map { it.group })
    }

    private val three = AccountTab("Three", "three", 0, "Other")

    @Test fun setGroupPutsAChatInANewGroupAndRoundTripsTheWireJson() {
        val grouped = base.setGroup(one.identity, "Runners", false)
        assertEquals(listOf("Runners", null), grouped.tabs.map { it.group })
        assertEquals(listOf(null, null), grouped.tabs.map { it.starredGroup })
        assertNull(grouped.groupOrder)
        val sent = grouped.json().getJSONArray("tabs")
        assertEquals("Runners", sent.getJSONObject(0).getString("group"))
        assertFalse(sent.getJSONObject(1).has("group"))
        assertEquals(grouped, AccountLayout.parse(JSONObject(grouped.json().toString())))
        assertEquals(listOf("Runners"), grouped.groupNames(false))
        assertEquals(emptyList<String>(), grouped.groupNames(true))
    }

    @Test fun setGroupJoinsAnExistingGroupWithoutDuplicatingIt() {
        val grouped = base.setGroup(one.identity, "Runners", false).setGroup(two.identity, "Runners", false)
        assertEquals(listOf("Runners", "Runners"), grouped.tabs.map { it.group })
        assertEquals(listOf("Runners"), grouped.groupNames(false))
        assertSame(grouped, grouped.setGroup(two.identity, "Runners", false))
    }

    @Test fun setGroupAppendsANewGroupAtTheEndOfAnExistingGroupOrder() {
        val ordered = base.copy(tabs = listOf(one.copy(group = "Runners"), two.copy(group = "Design"), three), groupOrder = listOf("Design", "Runners"))
        val added = ordered.setGroup(three.identity, "Product", false)
        assertEquals(listOf("Design", "Runners", "Product"), added.groupOrder)
        assertEquals(listOf("Design", "Runners", "Product"), added.groupNames(false))
        val sent = added.json().getJSONArray("group_order")
        assertEquals(listOf("Design", "Runners", "Product"), (0 until sent.length()).map { sent.getString(it) })
        val joined = ordered.setGroup(three.identity, "Runners", false)
        assertEquals(listOf("Design", "Runners"), joined.groupOrder)
    }

    @Test fun setGroupWithoutAnOrderLeavesTheOrderAbsent() {
        val grouped = base.copy(tabs = listOf(one.copy(group = "Runners"), two, three))
        assertNull(grouped.setGroup(two.identity, "Design", false).groupOrder)
        assertFalse(grouped.setGroup(two.identity, "Design", false).json().has("group_order"))
    }

    @Test fun setGroupToNoGroupWritesAnEmptyNameSoDesktopClearsIt() {
        val grouped = base.copy(tabs = listOf(one.copy(group = "Runners"), two.copy(group = "Runners")), groupOrder = listOf("Runners"))
        val loose = grouped.setGroup(one.identity, null, false)
        assertEquals(listOf("", "Runners"), loose.tabs.map { it.group })
        assertEquals("", loose.json().getJSONArray("tabs").getJSONObject(0).getString("group"))
        assertEquals(listOf("Runners"), loose.groupOrder)
        assertEquals(listOf("Runners"), loose.groupNames(false))
        val last = loose.setGroup(two.identity, null, false)
        assertNull(last.groupOrder)
        assertFalse(last.json().has("group_order"))
        assertEquals(emptyList<String>(), last.groupNames(false))
        assertSame(last, last.setGroup(two.identity, null, false))
        assertSame(base, base.setGroup(one.identity, null, false))
    }

    @Test fun setGroupMovingTheLastChatOutRemovesTheEmptyGroupFromTheOrder() {
        val grouped = base.copy(tabs = listOf(one.copy(group = "Runners"), two.copy(group = "Design")), groupOrder = listOf("Runners", "Design"))
        val moved = grouped.setGroup(one.identity, "Design", false)
        assertEquals(listOf("Design", "Design"), moved.tabs.map { it.group })
        assertEquals(listOf("Design"), moved.groupOrder)
    }

    @Test fun setGroupUsesTheFavoritesFieldOnlyInTheFavoritesView() {
        val grouped = base.copy(tabs = listOf(one.copy(group = "Runners"), two))
        val starred = grouped.setGroup(one.identity, "Hypertask", true)
        assertEquals("Runners", starred.tabs[0].group)
        assertEquals("Hypertask", starred.tabs[0].starredGroup)
        assertEquals("Hypertask", starred.json().getJSONArray("tabs").getJSONObject(0).getString("starred_group"))
        assertEquals(listOf("Hypertask"), starred.groupNames(true))
        assertEquals(listOf("Runners"), starred.groupNames(false))
        val cleared = starred.setGroup(one.identity, null, true)
        assertEquals("Runners", cleared.tabs[0].group)
        assertEquals("", cleared.tabs[0].starredGroup)
    }

    @Test fun setGroupRejectsBadNamesNamesHeldByTheOtherViewAndTooManyGroups() {
        val grouped = base.copy(tabs = listOf(one.copy(group = "Runners"), two.copy(starredGroup = "Hypertask")))
        listOf("", "   ", "a".repeat(65), "bad\nname").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { grouped.setGroup(two.identity, bad, false) }
        }
        assertThrows(IllegalArgumentException::class.java) { grouped.setGroup(two.identity, "Runners", true) }
        assertThrows(IllegalArgumentException::class.java) { grouped.setGroup(one.identity, "Hypertask", false) }
        val full = base.copy(tabs = (0 until MAX_GROUPS).map { AccountTab("T$it", "t$it", 0, "Other", group = "g$it") } + three)
        assertThrows(IllegalArgumentException::class.java) { full.setGroup(three.identity, "one more", false) }
        assertEquals("g0", full.setGroup(three.identity, "g0", false).tabs.last().group)
    }

    @Test fun setGroupOnAnUnknownChatChangesNothing() {
        assertSame(base, base.setGroup("0:missing", "Runners", false))
    }

    @Test fun ungroupReturnsEveryChatToLooseAndRemovesTheGroupFromTheOrder() {
        val grouped = base.copy(
            tabs = listOf(one.copy(group = "Runners"), two.copy(group = "Runners"), three.copy(group = "Design")),
            groupOrder = listOf("Design", "Runners"),
        )
        val loose = grouped.ungroup("Runners", false)
        assertEquals(listOf("", "", "Design"), loose.tabs.map { it.group })
        assertEquals(listOf("Design"), loose.groupOrder)
        assertEquals(listOf("Design"), loose.groupNames(false))
        val sent = loose.json().getJSONArray("tabs")
        assertEquals("", sent.getJSONObject(0).getString("group"))
        assertEquals("", sent.getJSONObject(1).getString("group"))
        assertEquals(loose, AccountLayout.parse(JSONObject(loose.json().toString())))
        assertNull(loose.ungroup("Design", false).groupOrder)
        assertSame(grouped, grouped.ungroup("Nothing here", false))
    }

    @Test fun ungroupLeavesTheOtherViewsGroupAndKeepsTheOrderWhileItIsStillHeld() {
        val both = base.copy(
            tabs = listOf(one.copy(group = "Runners", starredGroup = "Hypertask"), two.copy(starredGroup = "Runners")),
            groupOrder = listOf("Hypertask", "Runners"),
        )
        val loose = both.ungroup("Runners", false)
        assertEquals(listOf("", null), loose.tabs.map { it.group })
        assertEquals(listOf("Hypertask", "Runners"), loose.tabs.map { it.starredGroup })
        assertEquals(listOf("Hypertask", "Runners"), loose.groupOrder)
        val starredLoose = both.ungroup("Hypertask", true)
        assertEquals(listOf("", "Runners"), starredLoose.tabs.map { it.starredGroup })
        assertEquals(listOf("Runners"), starredLoose.groupOrder)
    }

    @Test fun phoneGroupEditsRebaseOntoADesktopChangeToOtherFields() {
        val start = base.copy(tabs = listOf(one.copy(group = "Runners"), two.copy(group = "Runners")), groupOrder = listOf("Runners"))
        val moved = start.setGroup(one.identity, "Design", false)
        val desktop = start.copy(revision = 2, tabs = start.tabs.map { if (it.session == "two") it.copy(favorite = true) else it })
        val saved = AccountLayout.rebase(start, moved, desktop)
        assertEquals(listOf("Design", "Runners"), saved.tabs.map { it.group })
        assertEquals(true, saved.tabs[1].favorite)

        val ungrouped = start.ungroup("Runners", false)
        val renamedOnDesktop = start.copy(revision = 3, tabs = start.tabs.map { if (it.session == "one") it.copy(name = "Desktop name", manual = true) else it })
        val savedLoose = AccountLayout.rebase(start, ungrouped, renamedOnDesktop)
        assertEquals(listOf("", ""), savedLoose.tabs.map { it.group })
        assertEquals("Desktop name", savedLoose.tabs[0].name)
        assertNull(savedLoose.groupOrder)
        assertEquals(listOf("", ""), AccountLayout.parse(JSONObject(savedLoose.json().toString())).tabs.map { it.group })
    }

    @Test fun phoneGroupOrderEditSurvivesADesktopGroupAddedMeanwhile() {
        val start = base.copy(tabs = listOf(one.copy(group = "Runners"), two, three), groupOrder = listOf("Runners"))
        val edited = start.setGroup(two.identity, "Design", false)
        val desktop = start.copy(revision = 2, tabs = listOf(one.copy(group = "Runners"), two, three.copy(group = "Product")), groupOrder = listOf("Runners", "Product"))
        val saved = AccountLayout.rebase(start, edited, desktop)
        assertEquals(listOf("Runners", "Design", "Product"), saved.tabs.map { it.group })
        assertEquals(listOf("Runners", "Design", "Product"), saved.groupOrder)
    }

    private fun favoritesGroupJson(): JSONObject = JSONObject("""{"schema":1,"revision":9,"workspaces":[{"name":"Other"}],"tabs":[{"kind":"tmux","name":"One","tmux_session":"one","tmux_host":0,"workspace":"Other","group":"Runners","starred_group":"Hypertask"},{"kind":"tmux","name":"Two","tmux_session":"two","tmux_host":1,"workspace":"Other","starred_group":""}],"group_order":["Hypertask","Runners"]}""")

    @Test fun desktopFavoritesGroupAndGroupOrderRoundTripTheWireJson() {
        val wire = favoritesGroupJson()
        val pulled = AccountLayout.parse(wire)
        assertEquals(listOf("Hypertask", ""), pulled.tabs.map { it.starredGroup })
        assertEquals(listOf("Runners", null), pulled.tabs.map { it.group })
        assertEquals(listOf("Hypertask", "Runners"), pulled.groupOrder)
        val sent = pulled.json()
        assertEquals("Hypertask", sent.getJSONArray("tabs").getJSONObject(0).getString("starred_group"))
        assertEquals("", sent.getJSONArray("tabs").getJSONObject(1).getString("starred_group"))
        assertEquals(listOf("Hypertask", "Runners"), (0 until sent.getJSONArray("group_order").length()).map { sent.getJSONArray("group_order").getString(it) })
        assertEquals(pulled, AccountLayout.parse(JSONObject(sent.toString())))
        assertEquals(pulled, AccountLayout.response(JSONObject().put("revision", 9).put("layout", wire)))
    }

    @Test fun absentFavoritesGroupAndGroupOrderStayAbsentOnTheWire() {
        val legacy = AccountLayout.parse(base.json())
        assertNull(legacy.groupOrder)
        assertNull(legacy.tabs[0].starredGroup)
        assertFalse(legacy.json().has("group_order"))
        assertFalse(legacy.json().getJSONArray("tabs").getJSONObject(0).has("starred_group"))
        val renamed = PendingLayout(legacy, legacy.renameTab(one.identity, "Phone")).edited
        assertFalse(renamed.json().has("group_order"))
        assertFalse(renamed.json().getJSONArray("tabs").getJSONObject(0).has("starred_group"))
    }

    @Test fun invalidFavoritesGroupAndGroupOrderFailClosed() {
        listOf<Any>(1, true, "a".repeat(65), "bad\nname", JSONObject.NULL).forEach { bad ->
            assertThrows(Exception::class.java) { AccountLayout.parse(favoritesGroupJson().apply { getJSONArray("tabs").getJSONObject(0).put("starred_group", bad) }) }
        }
        listOf<Any>(
            "Work", JSONObject.NULL, org.json.JSONArray(listOf("Work", "Work")), org.json.JSONArray(listOf("")), org.json.JSONArray(listOf(1)),
            org.json.JSONArray(listOf("a".repeat(65))), org.json.JSONArray((0..16).map { "g$it" }),
        ).forEach { bad ->
            assertThrows(Exception::class.java) { AccountLayout.parse(favoritesGroupJson().put("group_order", bad)) }
        }
        AccountLayout.parse(favoritesGroupJson().put("group_order", org.json.JSONArray((0 until 16).map { "g$it" })))
        AccountLayout.parse(favoritesGroupJson().put("group_order", org.json.JSONArray()))
    }

    @Test fun phoneEditsNeverDropDesktopFavoritesGroupsOrGroupOrder() {
        val pulled = AccountLayout.parse(favoritesGroupJson())
        val edited = pulled.renameTab(one.identity, "Phone name").setFavorite(two.identity, true)
        val saved = AccountLayout.rebase(pulled, edited, pulled)
        assertEquals(listOf("Hypertask", ""), saved.tabs.map { it.starredGroup })
        assertEquals(listOf("Hypertask", "Runners"), saved.groupOrder)
        assertEquals("Hypertask", saved.json().getJSONArray("tabs").getJSONObject(0).getString("starred_group"))
        assertTrue(saved.json().has("group_order"))
        val acknowledged = PendingLayout(pulled, edited).acknowledge(edited, pulled.copy(revision = 10))
        assertEquals(listOf("Hypertask", ""), acknowledged.edited.tabs.map { it.starredGroup })
        assertEquals(listOf("Hypertask", "Runners"), acknowledged.edited.groupOrder)
    }

    @Test fun desktopFavoritesGroupAndOrderWinOverAPhoneEditThatNeverTouchedThem() {
        val pulled = AccountLayout.parse(favoritesGroupJson())
        val edited = pulled.renameTab(one.identity, "Phone name")
        val desktop = pulled.copy(
            revision = 10,
            tabs = pulled.tabs.map { if (it.identity == one.identity) it.copy(starredGroup = "Product") else it.copy(starredGroup = "Hypertask") },
            groupOrder = listOf("Runners", "Product", "Hypertask"),
        )
        val result = PendingLayout(pulled, edited).pull(desktop)
        assertEquals(listOf("Product", "Hypertask"), result.edited.tabs.map { it.starredGroup })
        assertEquals("Phone name", result.edited.tabs[0].name)
        assertEquals(listOf("Runners", "Product", "Hypertask"), result.edited.groupOrder)
        assertEquals(listOf("Runners", "Product", "Hypertask"), result.base.groupOrder)
    }

    @Test fun anUnknownRemoteFavoritesGroupAndOrderStayUnknownAfterAPhoneEdit() {
        val legacy = AccountLayout.parse(base.json())
        val result = AccountLayout.rebase(legacy, legacy.renameTab(one.identity, "Phone"), legacy.copy(revision = 2))
        assertNull(result.groupOrder)
        assertEquals(listOf(null, null), result.tabs.map { it.starredGroup })
        val desktopAdds = legacy.copy(revision = 3, groupOrder = listOf("Hypertask"), tabs = legacy.tabs.map { it.copy(starredGroup = "Hypertask") })
        val merged = AccountLayout.rebase(legacy, legacy.renameTab(one.identity, "Phone"), desktopAdds)
        assertEquals(listOf("Hypertask"), merged.groupOrder)
        assertEquals(listOf("Hypertask", "Hypertask"), merged.tabs.map { it.starredGroup })
    }

    @Test fun groupOrderRebaseMatchesDesktopAndKeepsNamesAddedElsewhere() {
        val ordered = base.copy(groupOrder = listOf("Work", "Home"))
        val movedElsewhere = ordered.copy(revision = 4, groupOrder = listOf("Home", "Work"))
        assertEquals(listOf("Home", "Work"), AccountLayout.rebase(ordered, ordered, movedElsewhere).groupOrder)
        val mine = ordered.copy(groupOrder = listOf("Work", "Research", "Home"))
        assertEquals(listOf("Work", "Research", "Home"), AccountLayout.rebase(ordered, mine, movedElsewhere).groupOrder)
        val desktopAddedOne = movedElsewhere.copy(groupOrder = listOf("Home", "Work", "Product"))
        assertEquals(listOf("Work", "Research", "Home", "Product"), AccountLayout.rebase(ordered, mine, desktopAddedOne).groupOrder)
        val legacy = base
        assertEquals(listOf("Work"), AccountLayout.rebase(legacy, legacy.copy(groupOrder = listOf("Work")), legacy).groupOrder)
    }

    @Test fun aGroupOrderChangeAloneMakesTheLayoutDirty() {
        val ordered = base.copy(groupOrder = listOf("Work", "Home"))
        assertTrue(PendingLayout(ordered, ordered.copy(groupOrder = listOf("Home", "Work"))).dirty)
        assertFalse(PendingLayout(base, base.copy(groupOrder = emptyList())).dirty)
    }

    @Test fun favoritesSplitUsesTheFavoritesGroupAndOtherSplitsUseTheRegularGroup() {
        val tab = AccountTab("One", "one", 0, "Other", group = "Runners", starredGroup = "Hypertask")
        assertEquals("Hypertask", tab.groupIn(true))
        assertEquals("Runners", tab.groupIn(false))
        assertNull(AccountTab("Two", "two", 0, "Other", group = "Runners").groupIn(true))
    }
}
