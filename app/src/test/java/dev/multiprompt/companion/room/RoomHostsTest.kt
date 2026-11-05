package dev.multiprompt.companion.room

import dev.multiprompt.companion.model.HostProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomHostsTest {
    private val hetzner = HostProfile("h", "Hetzner", "hetzner.example", username = "valentin", keySecretId = "key")
    private val contabo = HostProfile("c", "Contabo", "contabo.example", username = "valentin", keySecretId = "key")

    private fun room(id: String) = RoomEntry(id, id, 1, emptyList())

    @Test
    fun everyServerIsAskedForRoomsOnce() {
        assertEquals(listOf("h", "c"), RoomHosts.candidates(listOf(hetzner), listOf(hetzner, contabo)).map { it.id })
    }

    @Test
    fun aRoomOnTheSecondServerIsListedAndRoutedThere() {
        val rooms = RoomHosts.merge(listOf("h" to emptyList(), "c" to listOf(room("contabo-room"))))
        assertEquals(listOf("contabo-room"), rooms.map { it.id })
        assertEquals(contabo, RoomHosts.hostFor(rooms.single(), listOf(hetzner), listOf(hetzner, contabo)))
    }

    @Test
    fun roomsFromEachServerKeepTheirOwnServer() {
        val rooms = RoomHosts.merge(listOf("h" to listOf(room("a")), "c" to listOf(room("b"))))
        assertEquals(listOf("h", "c"), rooms.map { it.hostId })
    }

    @Test
    fun aRoomWithNoKnownServerIsNeverSentToAnother() {
        assertNull(RoomHosts.hostFor(room("x"), listOf(hetzner), listOf(contabo)))
        assertNull(RoomHosts.hostFor(room("x").copy(hostId = "gone"), listOf(hetzner), listOf(contabo)))
        assertNull(RoomHosts.hostFor(null, listOf(hetzner), listOf(contabo)))
    }
}
