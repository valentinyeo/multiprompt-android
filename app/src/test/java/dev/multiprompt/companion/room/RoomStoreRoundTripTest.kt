package dev.multiprompt.companion.room

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import dev.multiprompt.companion.ui.RoomMember as RoomMemberUi
import dev.multiprompt.companion.ui.roomLines
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomStoreRoundTripTest {
    private val store = startStore()

    @After
    fun stopStore() {
        store.process.destroy()
        store.process.waitFor(10, TimeUnit.SECONDS)
    }

    @Test
    fun aPhoneSendReachesTheStoreAndItsRepliesComeBack() = runBlocking {
        val client = RoomClient(store.wire)
        val host = client.hostUuid()
        val roomId = createRoom(client, host)
        val room = client.members(roomId)
        assertEquals(2, room.members.size)
        assertTrue(room.revision >= 1)

        client.postHuman(roomId, client.revision(roomId), "ship it @sf-design-lead", listOf("sf-design-lead"), java.util.UUID.randomUUID().toString())

        val page = client.events(roomId)
        val human = page.events.single { it.type == "human" }
        assertEquals("ship it @sf-design-lead", human.text)
        assertNull(human.memberId)

        val deliveries = client.deliveries(roomId)
        assertEquals(1, deliveries.size)
        val delivery = deliveries.single()
        client.agentReply(roomId, delivery.eventId, delivery.memberId, java.util.UUID.randomUUID().toString(), "on it")

        val after = client.events(roomId)
        assertEquals(3, after.events.size)
        val reply = after.events.single { it.type == "agent_reply" }
        assertEquals("on it", reply.text)
        assertEquals(delivery.memberId, reply.memberId)

        val lines = roomLines(after.events, client.members(roomId).members.map { RoomMemberUi(it.id, it.alias, it.displayName, 0) })
        assertEquals(2, lines.size)
        assertNull(lines[0].author)
        assertEquals("SF Design Lead", lines[1].author?.name)
        assertEquals(listOf(2L, 3L), lines.map { it.seq })
    }

    @Test
    fun readingAgainNeverStoresTheSameMessageTwice() = runBlocking {
        val client = RoomClient(store.wire)
        val host = client.hostUuid()
        val roomId = createRoom(client, host)
        client.postHuman(roomId, client.revision(roomId), "hello room", emptyList(), java.util.UUID.randomUUID().toString())

        val first = client.events(roomId)
        val second = client.events(roomId, after = first.nextAfter)
        assertTrue(first.events.isNotEmpty())
        assertTrue(second.events.isEmpty())
        val merged = (first.events + second.events).distinctBy { it.seq }
        assertEquals(first.events.size, merged.size)
    }

    private suspend fun createRoom(client: RoomClient, host: String): String {
        val members = JSONArray()
            .put(member(host, "ht-product-lead", "HT Product Lead", "sid-1"))
            .put(member(host, "sf-design-lead", "SF Design Lead", "sid-2"))
        val result = store.wire.call(
            JSONObject()
                .put("op", "create")
                .put("command_id", java.util.UUID.randomUUID().toString())
                .put("name", "Launch room")
                .put("members", members),
        )
        assertTrue("create failed: $result", result.optBoolean("ok"))
        return result.getJSONObject("result").getJSONObject("event").getString("room_id")
    }

    private fun member(host: String, alias: String, name: String, sid: String) = JSONObject()
        .put("alias", alias)
        .put("display_name", name)
        .put("host_uuid", host)
        .put("provider", "claude")
        .put("provider_sid", sid)
        .put("session_identity", sid)

    private class Store(val process: Process, val wire: RoomWire)

    private fun startStore(): Store {
        val script = File("src/main/assets/rooms/room_service.py")
        assertTrue("room service script missing at $script", script.isFile)
        val state = Files.createTempDirectory("room-store").toFile()
        val process = ProcessBuilder("python3", "-u", script.absolutePath, "--serve", "--state-dir", state.absolutePath)
            .redirectErrorStream(false)
            .start()
        return Store(process, ProcessWire(process))
    }

    private class ProcessWire(private val process: Process) : RoomWire {
        override suspend fun call(request: JSONObject): JSONObject {
            val out = process.outputStream
            out.write((request.toString() + "\n").toByteArray())
            out.flush()
            val line = process.inputStream.bufferedReader().readLine()
            return JSONObject(line)
        }

        override fun close() {
            process.destroy()
        }
    }
}
