package dev.multiprompt.companion.account

import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import java.math.BigDecimal
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FrequentSplitTest {
    private val hour = 60 * 60 * 1000L
    private val now = 100 * hour
    private val host = HostProfile("v", "Hetzner", "vps", username = "valentin", keySecretId = "key")
    private val mapping = mapOf(0 to host, 1 to host.copy(id = "c"))
    private val session = TmuxSession(host.id, "one", 1, 0, 0)
    private val tab = AccountTab("One", "one", 0, "Other")
    private val layout = AccountLayout(1, listOf("Other"), listOf(tab))

    @Test fun usedRoundTripsWithoutInventingAbsentValues() {
        for (used in listOf(null, 0L, now, 9_007_199_254_740_991L)) {
            val original = layout.copy(tabs = listOf(tab.copy(used = used)))
            val json = JSONObject(original.json().toString())
            assertEquals(used != null, json.getJSONArray("tabs").getJSONObject(0).has("used"))
            val parsed = AccountLayout.response(JSONObject().put("revision", 1).put("layout", json))
            assertEquals(original, parsed)
            assertEquals(used, parsed.tabs.single().used)
        }
    }

    @Test fun usedRejectsNegativeFractionalUnsafeAndNonNumericValues() {
        for (used in listOf(-1, 0.5, BigDecimal("9007199254740990.5"), 9_007_199_254_740_992L,
            Long.MAX_VALUE, "123", true, JSONObject.NULL, JSONObject())) {
            val json = layout.json()
            json.getJSONArray("tabs").getJSONObject(0).put("used", used)
            assertThrows("used=$used", IllegalArgumentException::class.java) { AccountLayout.parse(json) }
        }
        for (used in listOf(-1L, 9_007_199_254_740_992L)) {
            assertThrows(IllegalArgumentException::class.java) { layout.copy(tabs = listOf(tab.copy(used = used))).validate() }
        }
    }

    @Test fun rebaseAlwaysKeepsMaximumUsedIncludingAbsentAndUnchangedValues() {
        for (baseUsed in listOf(null, 0L, now)) {
            for (localUsed in listOf(null, now - hour, now + hour)) {
                for (remoteUsed in listOf(null, now - 2 * hour, now + 2 * hour)) {
                    val base = layout.copy(tabs = listOf(tab.copy(used = baseUsed)))
                    val edited = base.copy(tabs = listOf(tab.copy(name = "Phone", used = localUsed)))
                    val remote = base.copy(revision = 2, tabs = listOf(tab.copy(favorite = true, used = remoteUsed)))
                    val pulled = PendingLayout(base, edited).pull(remote)
                    assertEquals(listOfNotNull(baseUsed, localUsed, remoteUsed).maxOrNull(), pulled.edited.tabs.single().used)
                    assertEquals("Phone", pulled.edited.tabs.single().name)
                    assertEquals(true, pulled.edited.tabs.single().favorite)
                    assertEquals(pulled, PendingLayout.parse(JSONObject(pulled.json().toString())))
                }
            }
        }
    }

    @Test fun concurrentTabAdditionAndPutAcknowledgementKeepNewerStamps() {
        val empty = layout.copy(tabs = emptyList())
        val added = layout.stampUsed(tab.identity, now)
        val remote = layout.stampUsed(tab.identity, now + hour).copy(revision = 2)
        assertEquals(now + hour, AccountLayout.rebase(empty, added, remote).tabs.single().used)
        assertEquals(now + hour, AccountLayout.rebase(empty, remote, added).tabs.single().used)
        val later = added.stampUsed(tab.identity, now + 2 * hour)
        val ack = PendingLayout(layout, later).acknowledge(added, remote)
        assertEquals(now + 2 * hour, ack.edited.tabs.single().used)
        assertTrue(ack.dirty)
        assertFalse(ack.acknowledge(ack.edited, ack.edited.copy(revision = 3)).dirty)
    }

    @Test fun visitCountWindowAndNewestAnywhereRules() {
        val newest = tab.copy(session = "elsewhere", visits = listOf(now + 2 * hour))
        for ((visits, included) in listOf(
            listOf(now - 5 * hour, now - 3 * hour, now - hour) to true,
            listOf(now - 2 * hour, now - hour) to false,
            listOf(now - 6 * hour, now - 2 * hour, now - hour) to true,
            listOf(now - 6 * hour - 1, now - 2 * hour, now - hour) to false,
            listOf(now + 1, now + 2, now + 3) to true,
            emptyList<Long>() to false,
            null to false,
        )) {
            val stamped = layout.copy(tabs = listOf(tab.copy(visits = visits), newest))
            assertEquals("visits=$visits", included, frequentSessions(listOf(session), stamped, mapping, now).isNotEmpty())
        }
        assertTrue(frequentSessions(listOf(session), layout.stampUsed(tab.identity, now), mapping, now).isEmpty())
        assertTrue(frequentSessions(listOf(session), null, mapping, now).isEmpty())
    }

    @Test fun localFrontCountsWithoutVisitsOrLayoutButOnlyOnThisDevice() {
        assertEquals(listOf(session), frequentSessions(listOf(session), null, mapping, now, session))
        assertTrue(frequentSessions(listOf(session), null, mapping, now, session.copy(hostId = "c")).isEmpty())
        assertTrue(frequentSessions(listOf(session), layout, mapping, now).isEmpty())
    }

    @Test fun newestVisitAcrossEntireLayoutCountsIncludingFutureAndBoundaryButNotStale() {
        for ((visit, included) in listOf(now - 6 * hour to true, now - 6 * hour - 1 to false, now + hour to true)) {
            val stamped = layout.copy(tabs = listOf(tab.copy(visits = listOf(visit))))
            assertEquals(included, frequentSessions(listOf(session), stamped, mapping, now).isNotEmpty())
        }
        val stamped = layout.copy(tabs = listOf(tab.copy(visits = listOf(now - hour)),
            tab.copy(session = "not-in-inventory", visits = listOf(now))))
        assertTrue(frequentSessions(listOf(session), stamped, mapping, now).isEmpty())
    }

    @Test fun frequentUsesAccountHostAndCaseInsensitiveMatchingAndOrdersByNewestVisitOrLocalNow() {
        val second = session.copy(name = "two", lastActivityEpochSeconds = Long.MAX_VALUE)
        val third = session.copy(name = "three")
        val sessions = listOf(second, session.copy(name = "ONE"), third, session.copy(hostId = "c"), session.copy(name = "missing"))
        val stamped = layout.copy(tabs = listOf(tab.copy(visits = listOf(now - 3, now - 2, now - 1)),
            tab.copy(session = "two", visits = listOf(now - 5, now - 4, now - 3)),
            tab.copy(session = "three", visits = listOf(now - 2))))
        assertEquals(listOf(sessions[1], second), frequentSessions(sessions, stamped, mapping, now))
        assertEquals(listOf(third, sessions[1], second), frequentSessions(sessions, stamped, mapping, now, third))
        assertTrue(frequentSessions(sessions, stamped, emptyMap(), now).isEmpty())
    }

    @Test fun sharedFixtureHasAlphaThenGammaOnScreenWithGammaAtBottom() {
        val fixture = JSONObject(javaClass.getResource("/igsh-415-frequent.json")!!.readText())
        val layout = AccountLayout.parse(fixture.getJSONObject("layout"))
        val sessions = layout.tabs.map { session.copy(name = it.session) }
        val newestFirst = frequentSessions(sessions, layout, mapping, fixture.getLong("now"))
        val screenTopToBottom = newestFirst.asReversed().map { it.name }
        val expected = fixture.getJSONArray("expectedScreenTopToBottom")
        assertEquals((0 until expected.length()).map { expected.getString(it) }, screenTopToBottom)
        assertEquals("gamma", screenTopToBottom.last())
    }

    @Test fun newVisitBypassesUsedThrottleEvenWhenUsedWasJustSent() {
        val received = layout.stampUsed(tab.identity, now)
        val throttle = UsedStampThrottle(received)
        val recent = received.stampUsed(tab.identity, now + 1)
        assertFalse(throttle.shouldPush(PendingLayout(received, recent)))
        val visited = recent.recordVisit(tab.identity, now)
        assertTrue(throttle.shouldPush(PendingLayout(received, visited)))
        assertFalse(throttle.shouldPush(PendingLayout(visited)))
    }

    @Test fun localStampNeverMovesBackwardsOrTouchesOtherTabs() {
        val original = layout.copy(tabs = listOf(tab.copy(used = now + hour), tab.copy(session = "two")))
        assertEquals(original, original.stampUsed(tab.identity, now))
        val stamped = original.stampUsed(tab.identity, now + 2 * hour)
        assertEquals(now + 2 * hour, stamped.tabs[0].used)
        assertEquals(original.tabs[1], stamped.tabs[1])
        assertEquals(original, original.stampUsed("0:missing", now))
    }

    @Test fun throttleUsesStrictTenMinuteBoundaryPerTabAndLastSentOrReceivedNotLastLocalStamp() {
        val received = layout.stampUsed(tab.identity, now)
        val throttle = UsedStampThrottle(received)
        for (delta in listOf(1L, 599_999L, 600_000L, 600_001L)) {
            val pending = PendingLayout(received, received.stampUsed(tab.identity, now + delta))
            assertEquals(delta > 600_000, throttle.shouldPush(pending))
        }
        val sent = received.stampUsed(tab.identity, now + 600_001)
        throttle.observe(sent)
        assertFalse(throttle.shouldPush(PendingLayout(received, sent)))
        assertFalse(throttle.shouldPush(PendingLayout(received, sent.stampUsed(tab.identity, now + 1_200_001))))
        assertTrue(throttle.shouldPush(PendingLayout(received, sent.stampUsed(tab.identity, now + 1_200_002))))
        throttle.observe(received)
        assertFalse(throttle.shouldPush(PendingLayout(received, sent)))
        val newerRemote = received.stampUsed(tab.identity, now + hour)
        throttle.observe(newerRemote)
        assertFalse(throttle.shouldPush(PendingLayout(received, sent)))
        val twoTabs = received.copy(tabs = received.tabs + tab.copy(session = "two"))
        assertTrue(throttle.shouldPush(PendingLayout(twoTabs, twoTabs.stampUsed("0:two", now))))
    }

    @Test fun throttleAllowsFirstStampAndDoesNotDelayOtherLayoutEdits() {
        val throttle = UsedStampThrottle(layout)
        assertTrue(throttle.shouldPush(PendingLayout(layout, layout.stampUsed(tab.identity, now))))
        val received = layout.stampUsed(tab.identity, now)
        throttle.observe(received)
        val recent = received.stampUsed(tab.identity, now + 1)
        assertFalse(throttle.shouldPush(PendingLayout(received, recent)))
        assertTrue(throttle.shouldPush(PendingLayout(received, recent.setFavorite(tab.identity, true))))
        assertTrue(throttle.shouldPush(PendingLayout(received, recent.renameTab(tab.identity, "New"))))
        assertTrue(throttle.shouldPush(PendingLayout(received, recent.closeTab(tab.identity))))
        assertFalse(throttle.shouldPush(PendingLayout(received)))
        assertFalse(throttle.shouldPush(PendingLayout(layout, layout.stampUsed(tab.identity, 0))))
    }

    @Test fun frequentSplitIsFirstInEitherOrientation() {
        val splits = listOf(null, "Other", "Frequent")
        for (right in listOf(false, true)) {
            val displayed = inboxSplitIds(splits, right)
            assertEquals(FREQUENT_SPLIT_ID, displayed.first())
            assertEquals(1, displayed.count { it == FREQUENT_SPLIT_ID })
            assertEquals(if (right) splits.asReversed() else splits,
                displayed.filterNot { it == FAVORITES_SPLIT_ID || it == FREQUENT_SPLIT_ID })
        }
        assertThrows(IllegalArgumentException::class.java) { layout.copy(workspaces = listOf(FREQUENT_SPLIT_ID)).validate() }
    }
}
