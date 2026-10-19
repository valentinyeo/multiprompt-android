package dev.multiprompt.companion.account

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionListSettingsLayoutTest {
    private val keys = listOf("frequent", "favorites", "all", "frequent_first")
    private val base = AccountLayout(1, listOf("Other"), listOf(AccountTab("one", "one", 0, "Other")))

    @Test fun settingsAreOptionalAndEmptyOrPartialObjectsRoundTripWithoutAddingDefaults() {
        for (settings in listOf<Map<String, Boolean>?>(null, emptyMap()) + keys.flatMap { key -> listOf(mapOf(key to false), mapOf(key to true)) } + listOf(keys.associateWith { false })) {
            val layout = base.copy(settings = settings)
            val json = JSONObject(layout.json().toString())
            assertEquals(settings != null, json.has("settings"))
            if (settings != null) assertEquals(settings.keys, json.getJSONObject("settings").keys().asSequence().toSet())
            assertEquals(layout, AccountLayout.parse(json))
            assertEquals(layout, AccountLayout.response(JSONObject().put("revision", 1).put("layout", json)))
        }
    }

    @Test fun invalidSettingsTypesAndUnknownKeysAreRejectedStrictly() {
        for (value in listOf(JSONObject.NULL, true, false, 1, 0, "true", "{}", JSONArray())) {
            assertThrows("settings=$value", IllegalArgumentException::class.java) {
                AccountLayout.parse(base.json().put("settings", value))
            }
        }
        for (key in keys) {
            for (value in listOf(JSONObject.NULL, 1, 0, "true", "false", JSONObject(), JSONArray())) {
                assertThrows("$key=$value", IllegalArgumentException::class.java) {
                    AccountLayout.parse(base.json().put("settings", JSONObject().put(key, value)))
                }
            }
        }
        for (key in listOf("unknown", "Frequent", "frequentFirst", "")) {
            assertThrows(IllegalArgumentException::class.java) {
                AccountLayout.parse(base.json().put("settings", JSONObject().put(key, true)))
            }
            assertThrows(IllegalArgumentException::class.java) { base.setSetting(key, false) }
        }
        assertThrows(IllegalArgumentException::class.java) { AccountLayout.parse(base.json().put("unknown", true)) }
    }

    @Test fun settingsParticipateInDirtyDetectionAndSurviveOtherEditsAndPendingStorage() {
        val settings = keys.associateWith { false }
        val layout = base.copy(settings = settings)
        assertFalse(base.sameContent(layout))
        assertFalse(base.sameContent(base.copy(settings = emptyMap())))
        val edited = layout.renameTab("0:one", "Renamed").setFavorite("0:one", true).renameWorkspace("Other", "Work")
            .stampInput("0:one", 100).recordVisit("0:one", 100)
        assertEquals(settings, edited.settings)
        assertEquals(settings, edited.closeTab("0:one").settings)
        val pending = PendingLayout(base, edited)
        assertTrue(pending.dirty)
        assertEquals(pending, PendingLayout.parse(JSONObject(pending.json().toString())))
        assertFalse(pending.acknowledge(edited, edited.copy(revision = 2)).dirty)
    }

    @Test fun rebaseMergesEverySettingPerKeyIncludingMissingValuesAndSameKeyConflicts() {
        for (key in keys) for (before in listOf(null, false, true)) for (local in listOf(null, false, true)) for (remote in listOf(null, false, true)) {
            fun layout(flag: Boolean?) = base.copy(settings = flag?.let { mapOf(key to it) })
            val original = layout(before)
            val edited = layout(local)
            val received = layout(remote).renameTab("0:one", "Remote").copy(revision = 2)
            val rebased = AccountLayout.rebase(original, edited, received)
            assertEquals("$key base=$before local=$local remote=$remote", if (local != before) local else remote, rebased.settings?.get(key))
            assertEquals("Remote", rebased.tabs.single().name)
            assertEquals(2L, rebased.revision)
        }
        val edited = base.setSetting("frequent", false).setSetting("frequent_first", false)
        val remote = base.setSetting("favorites", false).setSetting("all", false).copy(revision = 2)
        val merged = PendingLayout(base, edited).pull(remote)
        assertEquals(keys.associateWith { false }, merged.edited.settings)
        assertTrue(merged.dirty)
        val later = merged.edited.setSetting("frequent", true)
        val acknowledged = PendingLayout(merged.base, later).acknowledge(merged.edited, merged.edited.copy(revision = 3))
        assertEquals(keys.associateWith { it == "frequent" }, acknowledged.edited.settings)
        assertTrue(acknowledged.dirty)
        assertEquals(remote.settings, AccountLayout.rebase(base, base.renameTab("0:one", "Local"), remote).settings)
        assertEquals(emptyMap<String, Boolean>(), AccountLayout.rebase(base, base.copy(settings = emptyMap()), base).settings)
    }

    @Test fun splitVisibilityAndOrderingCoverEveryFlagCombinationInBothOrientations() {
        for (workspaces in listOf(listOf(null), listOf(null, "Other", "Work"))) {
            for (right in listOf(false, true)) for (mask in 0..15) {
                val frequent = mask and 1 != 0
                val favorites = mask and 2 != 0
                val all = mask and 4 != 0
                val first = mask and 8 != 0
                val actual = inboxSplitIds(workspaces, right, frequent, favorites, all, first)
                assertTrue(actual.isNotEmpty())
                assertEquals(actual.size, actual.distinct().size)
                assertEquals(frequent, FREQUENT_SPLIT_ID in actual)
                assertEquals(favorites, FAVORITES_SPLIT_ID in actual)
                assertEquals(all || (!frequent && !favorites && workspaces.size == 1), null in actual)
                assertEquals((if (right) workspaces.asReversed() else workspaces).filterNotNull(),
                    actual.filterNotNull().filterNot { it == FREQUENT_SPLIT_ID || it == FAVORITES_SPLIT_ID })
                if (frequent && first) assertEquals(FREQUENT_SPLIT_ID, actual.first())
                if (frequent && !first && favorites) assertEquals(actual.indexOf(FAVORITES_SPLIT_ID) + 1, actual.indexOf(FREQUENT_SPLIT_ID))
                if (frequent && !first && !favorites && all) assertEquals(actual.indexOf(null) + 1, actual.indexOf(FREQUENT_SPLIT_ID))
            }
        }
        assertEquals(listOf("Other", FREQUENT_SPLIT_ID), inboxSplitIds(listOf(null, "Other"), true, true, false, false, false))
        assertEquals(listOf(FREQUENT_SPLIT_ID, "Other"), inboxSplitIds(listOf(null, "Other"), false, true, false, false, false))
    }
}
