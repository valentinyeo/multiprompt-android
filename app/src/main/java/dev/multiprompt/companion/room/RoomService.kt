package dev.multiprompt.companion.room

import android.content.Context
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.ssh.SshRepository

class RoomService(private val context: Context, private val repository: SshRepository) {

    suspend fun open(host: HostProfile, accountId: String): RoomClient {
        val client = repository.connect(host)
        val wire = SshRoomWire(client, accountId, serviceSource, bridgeSource)
        return RoomClient(wire)
    }

    private suspend fun <T> onServerLink(host: HostProfile, accountId: String, block: suspend (RoomClient) -> T): T =
        repository.withServerLink(host, repeatable = true) { link ->
            RoomClient(SshRoomWire(link, accountId, serviceSource, bridgeSource)).use { block(it) }
        }

    suspend fun load(host: HostProfile, accountId: String, limit: Int = 20): List<RoomEntry> =
        onServerLink(host, accountId) { client ->
            client.hostUuid()
            client.rooms(limit).map { room ->
                runCatching { client.members(room.id) }.getOrElse { room }
            }
        }

    suspend fun page(host: HostProfile, accountId: String, roomId: String, after: Long = 0L, limit: Int = 40): RoomPage =
        onServerLink(host, accountId) { client ->
            client.hostUuid()
            client.events(roomId, after, limit)
        }

    suspend fun post(host: HostProfile, accountId: String, roomId: String, text: String, recipients: List<String>, commandId: String): Long =
        open(host, accountId).use { client ->
            if (!client.capabilities().liveSend) throw RoomFailure("agents_offline")
            client.postHuman(roomId, client.revision(roomId), text, recipients, commandId)
        }

    private val serviceSource by lazy { source(SERVICE) }
    private val bridgeSource by lazy { source(BRIDGE) }

    private fun source(name: String): String =
        context.assets.open("rooms/$name").bufferedReader().use { it.readText() }

    private companion object {
        const val SERVICE = "room_service.py"
        const val BRIDGE = "room_bridge.py"
    }
}
