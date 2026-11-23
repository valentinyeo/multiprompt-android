package dev.multiprompt.companion.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxListEndTest {
    @Test fun openingASplitRevealsTheNewestRow() {
        assertTrue(InboxListEnd().revealsNewest("a", 30, 0))
    }

    @Test fun switchingASplitRevealsTheNewestRowAfterAScrollElsewhere() {
        val end = InboxListEnd()
        assertTrue(end.revealsNewest("a", 30, 0))
        assertFalse(end.revealsNewest("a", 30, 21))
        assertTrue(end.revealsNewest("b", 30, 21))
        assertTrue(end.revealsNewest("a", 30, 21))
    }

    @Test fun rowsThatArriveWhileTheNewestRowShowsKeepItVisible() {
        val end = InboxListEnd()
        assertTrue(end.revealsNewest("a", 5, 0))
        assertTrue(end.revealsNewest("a", 6, 0))
        assertTrue(end.revealsNewest("a", 9, 0))
    }

    @Test fun rowsThatArriveWhileTheListSitsHigherLeaveTheListAlone() {
        val end = InboxListEnd()
        assertTrue(end.revealsNewest("a", 30, 0))
        assertFalse(end.revealsNewest("a", 30, 8))
        assertFalse(end.revealsNewest("a", 34, 8))
        assertFalse(end.revealsNewest("a", 34, 9))
    }

    @Test fun aSplitThatLoadsAfterTheSwitchStillRevealsTheNewestRow() {
        val end = InboxListEnd()
        assertFalse(end.revealsNewest("a", 0, 0))
        assertFalse(end.revealsNewest("b", 0, 17))
        assertTrue(end.revealsNewest("b", 30, 17))
    }

    @Test fun anEmptySplitAsksForNoScroll() {
        assertFalse(InboxListEnd().revealsNewest(null, 0, 0))
        assertFalse(InboxListEnd().revealsNewest("a", 0, 4))
    }
}
