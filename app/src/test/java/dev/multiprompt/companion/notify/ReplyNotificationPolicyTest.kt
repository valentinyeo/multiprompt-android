package dev.multiprompt.companion.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyNotificationPolicyTest {
    @Test fun firstRefreshOnlyBaselinesAndNeverNotifies() {
        val plan = ReplyNotificationPolicy.plan(null, mapOf("a" to 3, "b" to 1), null, emptySet())
        assertTrue(plan.notify.isEmpty())
    }

    @Test fun aNewReplyNotifiesOnlyTheSessionWhoseCountGrew() {
        val plan = ReplyNotificationPolicy.plan(mapOf("a" to 1, "b" to 0), mapOf("a" to 1, "b" to 1), null, setOf("a"))
        assertEquals(listOf("b"), plan.notify)
        assertTrue(plan.cancel.isEmpty())
    }

    @Test fun aSecondReplyInTheSameSessionNotifiesAgainSoItUpdatesInPlace() {
        val plan = ReplyNotificationPolicy.plan(mapOf("a" to 1), mapOf("a" to 2), null, setOf("a"))
        assertEquals(listOf("a"), plan.notify)
    }

    @Test fun theSessionOnScreenIsNeverNotifiedAndItsNotificationIsCleared() {
        val plan = ReplyNotificationPolicy.plan(mapOf("a" to 1), mapOf("a" to 2), "a", setOf("a"))
        assertTrue(plan.notify.isEmpty())
        assertEquals(setOf("a"), plan.cancel)
    }

    @Test fun readingASessionClearsItsNotificationAndKeepsOthers() {
        val plan = ReplyNotificationPolicy.plan(mapOf("a" to 1, "b" to 1), mapOf("a" to 0, "b" to 1), null, setOf("a", "b"))
        assertEquals(setOf("a"), plan.cancel)
        assertTrue(plan.notify.isEmpty())
    }

    @Test fun aVanishedSessionLosesItsNotification() {
        val plan = ReplyNotificationPolicy.plan(mapOf("a" to 1), emptyMap(), null, setOf("a"))
        assertEquals(setOf("a"), plan.cancel)
    }

    @Test fun anUnchangedCountDoesNotNotifyAgain() {
        val plan = ReplyNotificationPolicy.plan(mapOf("a" to 2), mapOf("a" to 2), null, setOf("a"))
        assertTrue(plan.notify.isEmpty())
    }
}
