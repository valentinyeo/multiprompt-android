package dev.multiprompt.companion.account

import java.math.BigDecimal
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InputStampTest {
    private val now = 1_800_000_000_000L
    private val tab = AccountTab("One", "one", 0, "Other")
    private val layout = AccountLayout(1, listOf("Other"), listOf(tab))

    @Test fun optionalInputRoundTripsAndRejectsInvalidValues() {
        for (input in listOf(null, 0L, now, 9_007_199_254_740_991L)) {
            val original = layout.copy(tabs = listOf(tab.copy(input = input)))
            assertEquals(input != null, original.json().getJSONArray("tabs").getJSONObject(0).has("input"))
            assertEquals(original, AccountLayout.parse(JSONObject(original.json().toString())))
        }
        for (input in listOf(-1, 0.5, BigDecimal("9007199254740990.5"), 9_007_199_254_740_992L,
            Long.MAX_VALUE, "123", true, JSONObject.NULL, JSONObject())) {
            val json = layout.json()
            json.getJSONArray("tabs").getJSONObject(0).put("input", input)
            assertThrows("input=$input", IllegalArgumentException::class.java) { AccountLayout.parse(json) }
        }
        for (input in listOf(-1L, 9_007_199_254_740_992L)) {
            assertThrows(IllegalArgumentException::class.java) { layout.copy(tabs = listOf(tab.copy(input = input))).validate() }
        }
    }

    @Test fun inputRebaseAndAcknowledgementKeepMaximumIncludingOmissionsAndConcurrentAddition() {
        for (b in listOf(null, 0L, now)) for (l in listOf(null, now - 1, now + 1)) for (r in listOf(null, now - 2, now + 2)) {
            val base = layout.copy(tabs = listOf(tab.copy(input = b)))
            val local = layout.copy(tabs = listOf(tab.copy(input = l)))
            val remote = layout.copy(revision = 2, tabs = listOf(tab.copy(input = r)))
            val expected = listOfNotNull(b, l, r).maxOrNull()
            assertEquals(expected, PendingLayout(base, local).pull(remote).edited.tabs.single().input)
            assertEquals(expected, PendingLayout(base, local).acknowledge(base, remote).edited.tabs.single().input)
            assertEquals(listOfNotNull(l, r).maxOrNull(), AccountLayout.rebase(layout.copy(tabs = emptyList()), local, remote).tabs.single().input)
        }
    }

    @Test fun stampsAreMonotonicAlsoKeepLegacyUsedAndDoNotTouchOtherTabs() {
        val original = layout.copy(tabs = listOf(tab, tab.copy(session = "two")))
        val stamped = original.stampInput(tab.identity, now)
        assertEquals(now, stamped.tabs.first().input)
        assertEquals(now, stamped.tabs.first().used)
        assertNull(stamped.tabs.first().visits)
        assertEquals(original.tabs.last(), stamped.tabs.last())
        assertEquals(stamped, stamped.stampInput(tab.identity, now - 1))
        assertEquals(original, original.stampInput("0:missing", now))
    }

    @Test fun firstInputPublishesImmediatelyIncludingZeroThenStrictSixtySecondBoundaryPerTab() {
        val throttle = UsedStampThrottle(layout)
        assertTrue(throttle.shouldPush(PendingLayout(layout, layout.stampInput(tab.identity, 0))))
        assertTrue(throttle.shouldPush(PendingLayout(layout, layout.stampInput(tab.identity, now))))
        val sent = layout.stampInput(tab.identity, now)
        throttle.observe(sent)
        for (delta in listOf(1L, 59_999L, 60_000L, 60_001L)) {
            assertEquals(delta > 60_000, throttle.shouldPush(PendingLayout(sent, sent.stampInput(tab.identity, now + delta))))
        }
        val pending = sent.stampInput(tab.identity, now + 60_001)
        throttle.observe(pending)
        throttle.observe(sent)
        assertFalse(throttle.shouldPush(PendingLayout(sent, pending)))
        val two = sent.copy(tabs = sent.tabs + tab.copy(session = "two"))
        assertTrue(throttle.shouldPush(PendingLayout(two, two.stampInput("0:two", now))))
        assertTrue(throttle.shouldPush(PendingLayout(sent, sent.stampInput(tab.identity, now + 1).recordVisit(tab.identity, now))))
        assertTrue(throttle.shouldPush(PendingLayout(sent, sent.stampInput(tab.identity, now + 1).setFavorite(tab.identity, true))))
    }
}
