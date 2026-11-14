package dev.multiprompt.companion.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomLoadPolicyTest {
    private fun load(trigger: RoomLoadTrigger, signedIn: Boolean = true, hostCount: Int = 2, inFlight: Boolean = false) =
        RoomLoadPolicy.shouldLoad(trigger, signedIn, hostCount, inFlight)

    @Test
    fun tenChatListRefreshesLoadRoomsZeroTimes() {
        val loads = (1..10).count { load(RoomLoadTrigger.LIST_REFRESH) }
        assertEquals(0, loads)
    }

    @Test
    fun openingTheRoomsScreenLoadsRooms() {
        assertTrue(load(RoomLoadTrigger.ROOMS_SCREEN))
    }

    @Test
    fun returningToTheAppLoadsRoomsOnce() {
        assertTrue(load(RoomLoadTrigger.APP_RESUMED))
        assertFalse(load(RoomLoadTrigger.APP_RESUMED, inFlight = true))
    }

    @Test
    fun noLoadWhenSignedOutOrWithoutServers() {
        assertFalse(load(RoomLoadTrigger.ROOMS_SCREEN, signedIn = false))
        assertFalse(load(RoomLoadTrigger.ROOMS_SCREEN, hostCount = 0))
    }

    @Test
    fun aLoadAlreadyRunningIsNotStartedTwice() {
        assertFalse(load(RoomLoadTrigger.ROOMS_SCREEN, inFlight = true))
    }
}
