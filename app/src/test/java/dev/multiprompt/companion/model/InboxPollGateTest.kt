package dev.multiprompt.companion.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxPollGateTest {
    private val gate = InboxPollGate()

    @Test
    fun aNewChecksumAsksForOneListingAndThenStaysQuiet() {
        assertTrue(gate.wantsListing("host", "1000 500"))
        assertFalse(gate.wantsListing("host", "1000 500"))
        assertFalse(gate.wantsListing("host", "1000 500"))
    }

    @Test
    fun aChangedChecksumAsksForTheListingAgain() {
        assertTrue(gate.wantsListing("host", "1000 500"))
        assertTrue(gate.wantsListing("host", "1001 512"))
        assertFalse(gate.wantsListing("host", "1001 512"))
        assertTrue(gate.wantsListing("host", "1000 500"))
    }

    @Test
    fun aFailedCheckAsksForTheListingEveryTime() {
        assertTrue(gate.wantsListing("host", null))
        assertTrue(gate.wantsListing("host", null))
        assertTrue(gate.wantsListing("host", "1000 500"))
        assertFalse(gate.wantsListing("host", "1000 500"))
    }

    @Test
    fun aFailedListingAsksForTheListingAgain() {
        gate.wantsListing("host", "1000 500")
        gate.forget("host")
        assertTrue(gate.wantsListing("host", "1000 500"))
    }

    @Test
    fun everyHostKeepsItsOwnChecksum() {
        assertTrue(gate.wantsListing("one", "1000 500"))
        assertTrue(gate.wantsListing("two", "2000 700"))
        assertFalse(gate.wantsListing("one", "1000 500"))
        assertFalse(gate.wantsListing("two", "2000 700"))
        assertTrue(gate.wantsListing("one", "3000 900"))
        assertFalse(gate.wantsListing("two", "2000 700"))
    }

    @Test
    fun forgettingEveryHostAsksForTheListingAgain() {
        gate.wantsListing("one", "1000 500")
        gate.wantsListing("two", "2000 700")
        gate.forgetAll()
        assertTrue(gate.wantsListing("one", "1000 500"))
        assertTrue(gate.wantsListing("two", "2000 700"))
    }
}
