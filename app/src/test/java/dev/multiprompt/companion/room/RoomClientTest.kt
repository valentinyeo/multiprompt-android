package dev.multiprompt.companion.room

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomClientTest {
    private class FakeWire(private val answers: MutableList<JSONObject>) : RoomWire {
        val requests = mutableListOf<JSONObject>()
        var closed = false
        override suspend fun call(request: JSONObject): JSONObject {
            requests += request
            return answers.removeFirst()
        }
        override fun close() {
            closed = true
        }
    }

    private fun ok(result: JSONObject) = JSONObject().put("ok", true).put("result", result)

    @Test fun roomsComeBackWithTheirMembers() {
        val wire = FakeWire(
            mutableListOf(
                ok(JSONObject().put("room_protocol", 1).put("host_uuid", "u-1").put("store_only", true)),
                ok(
                    JSONObject().put("through", 4).put("next_after", 2)
                        .put(
                            "rooms",
                            org.json.JSONArray()
                                .put(JSONObject().put("id", "r-1").put("name", "Launch").put("revision", 1).put("creation_seq", 1))
                                .put(JSONObject().put("id", "r-2").put("name", "Money").put("revision", 2).put("creation_seq", 2)),
                        ),
                ),
                ok(
                    JSONObject().put("room", JSONObject().put("id", "r-1").put("name", "Launch").put("revision", 1)
                        .put("members", org.json.JSONArray()
                            .put(JSONObject().put("alias", "poteto").put("display_name", "HT Product Lead").put("session_identity", "claude:a"))
                            .put(JSONObject().put("alias", "design").put("display_name", "SF Design Lead").put("session_identity", "claude:b")))),
                ),
                ok(JSONObject().put("room", JSONObject().put("id", "r-2").put("name", "Money").put("revision", 2).put("members", org.json.JSONArray()))),
            ),
        )
        val client = RoomClient(wire)
        assertEquals("u-1", runBlocking { client.hostUuid() })
        val rooms = runBlocking { client.rooms() }
        assertEquals(listOf("r-1", "r-2"), rooms.map { it.id })
        val first = runBlocking { client.members("r-1") }
        assertEquals(listOf("HT Product Lead", "SF Design Lead"), first.members.map { it.displayName })
        assertEquals(listOf("poteto", "design"), first.members.map { it.alias })
        assertEquals("list", wire.requests[1].getString("op"))
        assertEquals(0, wire.requests[1].getInt("after"))
        client.close()
        assertTrue(wire.closed)
    }

    @Test fun aStoreThatRefusesTheRequestSaysWhy() {
        val wire = FakeWire(mutableListOf(JSONObject().put("ok", false).put("error", "different_host")))
        val failure = runCatching { runBlocking { RoomClient(wire).hostUuid() } }.exceptionOrNull()
        assertTrue(failure is RoomFailure)
        assertEquals("different_host", (failure as RoomFailure).code)
    }

    @Test fun theStoreDirectoryKeyIsTheAccountHashedToSixtyFourHex() {
        val key = SshRoomWire.accountKey("account-123")
        assertEquals(64, key.length)
        assertTrue(key.all { it in "0123456789abcdef" })
        assertEquals(key, SshRoomWire.accountKey("account-123"))
    }
}
