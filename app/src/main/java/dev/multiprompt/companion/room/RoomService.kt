package dev.multiprompt.companion.room

import android.content.Context
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.ssh.SshRepository

class RoomService(private val context: Context, private val repository: SshRepository) {

    suspend fun open(host: HostProfile, accountId: String): RoomClient {
        val client = repository.connect(host)
        val wire = SshRoomWire(client, accountId, source(SERVICE), source(BRIDGE))
        return RoomClient(wire)
    }

    suspend fun load(host: HostProfile, accountId: String, limit: Int = 20): List<RoomEntry> =
        open(host, accountId).use { client ->
            client.hostUuid()
            client.rooms(limit).map { room ->
                runCatching { client.members(room.id) }.getOrElse { room }
            }
        }

    suspend fun page(host: HostProfile, accountId: String, roomId: String, after: Long = 0L, limit: Int = 40): RoomPage =
        open(host, accountId).use { client ->
            client.hostUuid()
            client.events(roomId, after, limit)
        }

    suspend fun post(host: HostProfile, accountId: String, roomId: String, text: String, recipients: List<String>, commandId: String): Long =
        open(host, accountId).use { client ->
            client.hostUuid()
            client.postHuman(roomId, client.revision(roomId), text, recipients, commandId)
        }

    private fun source(name: String): String =
        context.assets.open("rooms/$name").bufferedReader().use { it.readText() }

    private companion object {
        const val SERVICE = "room_service.py"
        const val BRIDGE = "room_bridge.py"
    }
}
