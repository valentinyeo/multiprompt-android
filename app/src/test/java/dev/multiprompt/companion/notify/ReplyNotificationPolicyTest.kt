package dev.multiprompt.companion.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyNotificationPolicyTest {
    @Test fun firstRefreshOnlyBaselinesAndNeverNotifies() {
        val plan = ReplyNotificationPolicy.plan(false, setOf("a", "b"), mapOf("a" to 3, "b" to 1), null, emptySet(), setOf("a", "b"))
        assertTrue(plan.notify.isEmpty())
    }

    @Test fun aNewReplyNotifiesOnlyTheSessionThatReceivedIt() {
        val plan = ReplyNotificationPolicy.plan(true, setOf("b"), mapOf("a" to 1, "b" to 1), null, setOf("a"), setOf("a", "b"))
        assertEquals(listOf("b"), plan.notify)
        assertTrue(plan.cancel.isEmpty())
    }

    @Test fun aCountThatMovedWithoutAReplyDoesNotNotify() {
        val plan = ReplyNotificationPolicy.plan(true, emptySet(), mapOf("a" to 1), null, emptySet(), setOf("a", "b"))
        assertTrue(plan.notify.isEmpty())
    }

    @Test fun aSecondReplyInTheSameSessionNotifiesAgainSoItUpdatesInPlace() {
        val plan = ReplyNotificationPolicy.plan(true, setOf("a"), mapOf("a" to 2), null, setOf("a"), setOf("a", "b"))
        assertEquals(listOf("a"), plan.notify)
    }

    @Test fun theSessionOnScreenIsNeverNotifiedAndItsNotificationIsCleared() {
        val plan = ReplyNotificationPolicy.plan(true, setOf("a"), mapOf("a" to 2), "a", setOf("a"), setOf("a", "b"))
        assertTrue(plan.notify.isEmpty())
        assertEquals(setOf("a"), plan.cancel)
    }

    @Test fun readingASessionClearsItsNotificationAndKeepsOthers() {
        val plan = ReplyNotificationPolicy.plan(true, emptySet(), mapOf("a" to 0, "b" to 1), null, setOf("a", "b"), setOf("a", "b"))
        assertEquals(setOf("a"), plan.cancel)
        assertTrue(plan.notify.isEmpty())
    }

    @Test fun aVanishedSessionLosesItsNotification() {
        val plan = ReplyNotificationPolicy.plan(true, emptySet(), emptyMap(), null, setOf("a"), setOf("a", "b"))
        assertEquals(setOf("a"), plan.cancel)
    }

    @Test fun aReplyToAMutedOrArchivedSessionDoesNotNotify() {
        val plan = ReplyNotificationPolicy.plan(true, setOf("a"), mapOf("a" to 0), null, emptySet(), setOf("a", "b"))
        assertTrue(plan.notify.isEmpty())
    }

    @Test fun favoritesFilterRepliesAndClearNotificationsWhenTheStarChanges() {
        fun plan(favorites: Set<String>, active: Set<String> = emptySet()) =
            ReplyNotificationPolicy.plan(true, setOf("a", "b"), mapOf("a" to 1, "b" to 1), null, active, favorites)
        assertEquals(emptyList<String>(), plan(emptySet()).notify)
        assertEquals(listOf("a"), plan(setOf("a")).notify)
        assertEquals(setOf("a"), plan(setOf("b"), setOf("a")).cancel)
        assertEquals(listOf("b"), plan(setOf("b")).notify)
    }

}
