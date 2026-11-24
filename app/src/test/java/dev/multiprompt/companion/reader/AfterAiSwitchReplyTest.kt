package dev.multiprompt.companion.reader

import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AfterAiSwitchReplyTest {
    @Test fun archivedAndStreamedPiRepliesAppearOnceWithoutHidingEqualReplies() {
        val check = File("src/test/resources/reader/after_ai_switch.py")
        val reader = File("src/main/assets/chat_history_reader.py")
        val process = ProcessBuilder("python3", "-B", check.absolutePath, reader.absolutePath)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        assertEquals(output, 0, process.exitValue())
        val stages = JSONArray(output)
        val sid = "switched-pi-2569"
        val scope = MessageProtocol.Scope("pi", sid, "verify-session")
        val turns = listOf("first", "second", "after-restart")
        for (index in 0 until stages.length()) {
            val stage = stages.getJSONObject(index)
            val rows = stage.getJSONArray("rows")
            val history = ChatHistory(sid = sid, inode = 7, rows = (0 until rows.length()).map {
                requireNotNull(ChatHistoryProtocol.parseRow(rows.getJSONObject(it)))
            })
            val snapshot = JSONObject().put("id", 1).put("message_protocol", 1).put("result", JSONObject()
                .put("snapshot_id", "switched").put("epoch", 1).put("seq", stage.getJSONArray("events").length())
                .put("next_cursor", JSONObject.NULL).put("items", stage.getJSONArray("events")))
            val replica = MessageReplica.snapshot(listOf(MessageProtocol.snapshot(snapshot.toString(), 1, scope)))
            val expected = turns.take(index / 2 + 1).flatMap { listOf("phone-$it", "PI_REPLY_2569") }
            for (reconnected in listOf(replica, MessageReplica.snapshot(listOf(MessageProtocol.snapshot(snapshot.toString(), 1, scope))))) {
                assertEquals("AI_SWITCH_REPLY_DUPLICATE stage=$index", expected, history.projectMessages(reconnected).map { it.text })
            }
        }
    }
}
