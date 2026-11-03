package dev.multiprompt.companion.room

import org.json.JSONObject

data class RoomMemberEntry(val id: String, val alias: String, val displayName: String, val sessionIdentity: String)

data class RoomEntry(val id: String, val name: String, val revision: Int, val members: List<RoomMemberEntry>)

data class RoomEvent(val seq: Long, val type: String, val text: String, val memberId: String?)

data class RoomPage(val events: List<RoomEvent>, val nextAfter: Long)

class RoomClient(private val wire: RoomWire) : AutoCloseable {

    suspend fun hostUuid(): String {
        val result = call(JSONObject().put("op", "capabilities"))
        return result.optString("host_uuid").takeIf { it.isNotBlank() }
            ?: throw RoomFailure("missing_host_uuid")
    }

    suspend fun rooms(limit: Int = 100): List<RoomEntry> {
        val result = call(JSONObject().put("op", "list").put("after", 0).put("through", JSONObject.NULL).put("limit", limit))
        val rows = result.optJSONArray("rooms") ?: throw RoomFailure("missing_rooms")
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = row.optString("name").takeIf { it.isNotBlank() } ?: id
            RoomEntry(id, name, row.optInt("revision"), emptyList())
        }
    }

    suspend fun members(roomId: String): RoomEntry {
        val result = call(JSONObject().put("op", "snapshot").put("room_id", roomId))
        val room = result.optJSONObject("room") ?: result
        val rows = room.optJSONArray("members")
        val members = (0 until (rows?.length() ?: 0)).mapNotNull { index ->
            val member = rows?.optJSONObject(index) ?: return@mapNotNull null
            val alias = member.optString("alias").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = member.optString("display_name").takeIf { it.isNotBlank() } ?: alias
            RoomMemberEntry(member.optString("id"), alias, name, member.optString("session_identity"))
        }
        return RoomEntry(
            room.optString("id", roomId),
            room.optString("name", roomId),
            room.optInt("revision"),
            members,
        )
    }

    suspend fun events(roomId: String, after: Long = 0L, limit: Int = 40): RoomPage {
        val result = call(JSONObject().put("op", "events").put("room_id", roomId).put("after", after).put("limit", limit))
        val rows = result.optJSONArray("events") ?: throw RoomFailure("missing_events")
        val events = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val type = row.optString("type").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RoomEvent(
                seq = row.optLong("seq"),
                type = type,
                text = row.optString("text"),
                memberId = row.optString("member_id").takeIf { it.isNotBlank() },
            )
        }
        return RoomPage(events, result.optLong("next_after", after))
    }

    private suspend fun call(request: JSONObject): JSONObject {
        val response = wire.call(request)
        if (!response.optBoolean("ok", false)) {
            throw RoomFailure(response.optString("error").ifBlank { "unknown" })
        }
        return response.optJSONObject("result") ?: JSONObject()
    }

    override fun close() = wire.close()
}
