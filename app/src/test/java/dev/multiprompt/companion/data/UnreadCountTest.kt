package dev.multiprompt.companion.data

import org.junit.Assert.assertEquals
import org.junit.Test

class UnreadCountTest {
    @Test
    fun twoFinishedTurnsCountTwo() {
        var state = UnreadCount.step(UnreadCount.State(), 10, waiting = false, unread = true)
        state = UnreadCount.step(state, 20, waiting = true, unread = true)
        state = UnreadCount.step(state, 30, waiting = false, unread = true)
        state = UnreadCount.step(state, 40, waiting = true, unread = true)
        assertEquals(2, state.count)
    }

    @Test
    fun repeatedWaitingRefreshDoesNotDoubleCount() {
        val working = UnreadCount.step(UnreadCount.State(), 10, waiting = false, unread = true)
        val finished = UnreadCount.step(working, 20, waiting = true, unread = true)
        assertEquals(1, finished.count)
        assertEquals(finished, UnreadCount.step(finished, 20, waiting = true, unread = true))
        val workingAgain = UnreadCount.step(finished, 20, waiting = false, unread = true)
        assertEquals(1, UnreadCount.step(workingAgain, 20, waiting = true, unread = true).count)
    }

    @Test
    fun newerWaitingSnapshotCountsWithoutObservedWorking() {
        val older = UnreadCount.step(UnreadCount.State(), 10, waiting = true, unread = true)
        assertEquals(0, older.count)
        assertEquals(1, UnreadCount.step(older, 20, waiting = true, unread = true).count)
    }

    @Test
    fun readResetsAndDoesNotRecountReadActivity() {
        val read = UnreadCount.read(UnreadCount.State(count = 2, lastCountedActivity = 20), 30, waiting = true)
        assertEquals(0, read.count)
        assertEquals(0, UnreadCount.step(read, 30, waiting = true, unread = true).count)
        assertEquals(1, UnreadCount.step(read, 40, waiting = true, unread = true).count)
        assertEquals(0, UnreadCount.step(read.copy(count = 2), 40, waiting = true, unread = false).count)
    }

    @Test
    fun unreadWithNoObservedTurnsDisplaysOne() {
        assertEquals(1, UnreadCount.displayed(0, unread = true))
        assertEquals(0, UnreadCount.displayed(2, unread = false))
        assertEquals(1, UnreadCount.unread(UnreadCount.State()).count)
        assertEquals(3, UnreadCount.unread(UnreadCount.State(count = 3)).count)
    }

    @Test
    fun oldSnapshotsDoNotInventFinishedTurns() {
        val working = UnreadCount.State(lastActivity = 30, lastCountedActivity = 10, wasWorking = true)
        assertEquals(working, UnreadCount.step(working, 20, waiting = true, unread = true))
        assertEquals(1, UnreadCount.step(working, 30, waiting = true, unread = true).count)
    }

    @Test
    fun labelsCapAt99Plus() {
        (1..99).forEach { assertEquals(it.toString(), UnreadCount.label(it)) }
        assertEquals("99+", UnreadCount.label(100))
        assertEquals("99+", UnreadCount.label(150))
    }
}
