package dev.multiprompt.companion.room

import dev.multiprompt.companion.model.HostProfile

object RoomHosts {
    fun candidates(accountHosts: Collection<HostProfile>, hosts: List<HostProfile>): List<HostProfile> =
        (accountHosts + hosts).distinctBy { it.id }

    fun merge(loaded: List<Pair<String, List<RoomEntry>>>): List<RoomEntry> =
        loaded.flatMap { (hostId, rooms) -> rooms.map { it.copy(hostId = hostId) } }.distinctBy { it.id }

    fun hostFor(room: RoomEntry?, accountHosts: Collection<HostProfile>, hosts: List<HostProfile>): HostProfile? {
        val known = room?.hostId?.takeIf { it.isNotEmpty() } ?: return null
        return candidates(accountHosts, hosts).firstOrNull { it.id == known }
    }
}
