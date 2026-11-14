package dev.multiprompt.companion.room

enum class RoomLoadTrigger { ROOMS_SCREEN, APP_RESUMED, LIST_REFRESH }

object RoomLoadPolicy {
    fun shouldLoad(trigger: RoomLoadTrigger, signedIn: Boolean, hostCount: Int, inFlight: Boolean): Boolean =
        trigger != RoomLoadTrigger.LIST_REFRESH && signedIn && hostCount > 0 && !inFlight
}
