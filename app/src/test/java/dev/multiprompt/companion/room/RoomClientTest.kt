package dev.multiprompt.companion.room

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomClientTest {
    private class FakeWire(private val answers: MutableList<JSONObject>) : RoomWire {
        val requests = mutableListOf<JSONObject>()
        var closed = false
        override suspend fun call(request: JSONObject): JSONObject {
            requests += request
            return answers.removeAt(0)
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

    @Test fun theClientOptsIntoRoomRepliesAndNamesItsHostOnEveryCall() {
        val wire = FakeWire(
            mutableListOf(
                ok(JSONObject().put("host_uuid", "u-1").put("store_only", false).put("live_send", true).put("room_reply_protocol", 1)),
                ok(
                    JSONObject().put("next_after", 3).put(
                        "events",
                        org.json.JSONArray()
                            .put(JSONObject().put("id", "e-1").put("seq", 2).put("type", "human").put("text", "First"))
                            .put(JSONObject().put("id", "e-2").put("seq", 3).put("type", "agent_reply").put("text", "Answer").put("member_id", "m-1").put("parent_event_id", "e-1")),
                    ),
                ),
            ),
        )
        val client = RoomClient(wire)
        val capabilities = runBlocking { client.capabilities() }
        assertTrue(capabilities.liveSend)
        assertEquals(1, wire.requests[0].getInt("room_messages"))
        assertTrue(!wire.requests[0].has("host_uuid"))
        val page = runBlocking { client.events("r-1") }
        assertEquals("u-1", wire.requests[1].getString("host_uuid"))
        assertEquals("e-1", page.events[1].parentEventId)
        assertEquals("e-2", page.events[1].id)
    }

    @Test fun anOldStoreWithoutReplyCollectionCannotSend() {
        val wire = FakeWire(mutableListOf(ok(JSONObject().put("host_uuid", "u-1").put("store_only", true).put("live_send", false))))
        assertTrue(!runBlocking { RoomClient(wire).capabilities() }.liveSend)
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
