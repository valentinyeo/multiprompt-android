package dev.multiprompt.companion.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyNotificationPolicyTest {
    @Test fun firstRefreshOnlyBaselinesAndNeverNotifies() {
        val plan = ReplyNotificationPolicy.plan(false, setOf("a", "b"), mapOf("a" to 3, "b" to 1), null, emptySet())
        assertTrue(plan.notify.isEmpty())
    }

    @Test fun aNewReplyNotifiesOnlyTheSessionThatReceivedIt() {
        val plan = ReplyNotificationPolicy.plan(true, setOf("b"), mapOf("a" to 1, "b" to 1), null, setOf("a"))
        assertEquals(listOf("b"), plan.notify)
        assertTrue(plan.cancel.isEmpty())
    }

    @Test fun aCountThatMovedWithoutAReplyDoesNotNotify() {
        val plan = ReplyNotificationPolicy.plan(true, emptySet(), mapOf("a" to 1), null, emptySet())
        assertTrue(plan.notify.isEmpty())
    }

    @Test fun aSecondReplyInTheSameSessionNotifiesAgainSoItUpdatesInPlace() {
        val plan = ReplyNotificationPolicy.plan(true, setOf("a"), mapOf("a" to 2), null, setOf("a"))
        assertEquals(listOf("a"), plan.notify)
    }

    @Test fun theSessionOnScreenIsNeverNotifiedAndItsNotificationIsCleared() {
        val plan = ReplyNotificationPolicy.plan(true, setOf("a"), mapOf("a" to 2), "a", setOf("a"))
        assertTrue(plan.notify.isEmpty())
        assertEquals(setOf("a"), plan.cancel)
    }

    @Test fun readingASessionClearsItsNotificationAndKeepsOthers() {
        val plan = ReplyNotificationPolicy.plan(true, emptySet(), mapOf("a" to 0, "b" to 1), null, setOf("a", "b"))
        assertEquals(setOf("a"), plan.cancel)
        assertTrue(plan.notify.isEmpty())
    }

    @Test fun aVanishedSessionLosesItsNotification() {
        val plan = ReplyNotificationPolicy.plan(true, emptySet(), emptyMap(), null, setOf("a"))
        assertEquals(setOf("a"), plan.cancel)
    }

    @Test fun aReplyToAMutedOrArchivedSessionDoesNotNotify() {
        val plan = ReplyNotificationPolicy.plan(true, setOf("a"), mapOf("a" to 0), null, emptySet())
        assertTrue(plan.notify.isEmpty())
    }
}
