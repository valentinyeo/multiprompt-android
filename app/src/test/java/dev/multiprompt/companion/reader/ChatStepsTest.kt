package dev.multiprompt.companion.reader

import java.io.File
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatStepsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun row(json: String) = ChatHistoryProtocol.parseRow(JSONObject(json))

    private val rows = listOf(
        row("""{"role":"user","text":"check it","id":"u1","timestamp":"","offset":0,"end":118}"""),
        row("""{"role":"assistant","text":"Looking.","id":"a1","segment":0,"timestamp":"","offset":118,"end":385}"""),
        row("""{"role":"tool","id":"t1","timestamp":"","text":"Ran List files","status":"ok","detail":"{\"command\":\"ls -la\"}\n\none\ntwo","offset":118,"end":385,"result_offset":385}"""),
        row("""{"role":"tool","id":"t2","timestamp":"","text":"Read Main.kt","status":"running","detail":"{\"file_path\":\"/x/Main.kt\"}","offset":550,"end":756}"""),
        row("""{"role":"assistant","text":"Done.","id":"a3","timestamp":"","offset":756,"end":881}"""),
    )

    private fun history(source: List<ChatHistoryProtocol.Row>) = ChatHistory("sid", 7, 881, 0, 0, "", false, source)

    @Test
    fun stepRowsStayOutOfMessagesAndGroupAboveTheNextReply() {
        val history = history(rows)
        assertEquals(listOf("check it", "Looking.", "Done."), history.messages.map { it.text })
        assertEquals(listOf("check it", "Looking.", "Done."), history.managedMessages(null, emptyList()).map { it.text })
        val steps = ChatSteps.of(history.rows)
        assertEquals(listOf("Ran List files", "Read Main.kt"), steps.map { it.text })
        assertEquals(listOf("{\"command\":\"ls -la\"}", "one", "two"), steps.first().output)
        val placement = ChatSteps.place(history.messages, steps)
        assertEquals(setOf("sid:7:assistant:a3"), placement.above.keys)
        assertEquals(listOf("t1", "t2"), placement.above.getValue("sid:7:assistant:a3").steps.map { it.id })
        assertNull(placement.trailing)
    }

    @Test
    fun foldedLineCountsSteps() {
        val group = ChatSteps.place(history(rows).messages, ChatSteps.of(rows)).above.values.single()
        assertEquals("› 2 steps", ChatSteps.label(group, open = false))
        assertEquals("⌄ 2 steps", ChatSteps.label(group, open = true))
        assertEquals("› 1 step", ChatSteps.label(group.copy(steps = group.steps.take(1)), open = false))
    }

    @Test
    fun stepsAfterTheLastMessageWaitForTheLiveReplyOrTrail() {
        val trailing = rows.take(4)
        val messages = history(trailing).messages
        val steps = ChatSteps.of(trailing)
        val alone = ChatSteps.place(messages, steps)
        assertEquals(listOf("t1", "t2"), alone.trailing!!.steps.map { it.id })
        assertEquals(emptySet<String>(), alone.above.keys)
        val live = messages + ChatHistoryMessage("@live:1:0", "assistant", "Working", "", 0, 0, pending = true)
        val placed = ChatSteps.place(live, steps)
        assertEquals(listOf("t1", "t2"), placed.above.getValue("@live:1:0").steps.map { it.id })
        assertNull(placed.trailing)
    }

    @Test
    fun resultFromALaterPageJoinsItsCall() {
        val call = row("""{"role":"tool","id":"t9","timestamp":"","text":"Ran tests","status":"running","detail":"gradle test","offset":10,"end":20}""")
        val result = row("""{"role":"tool_result","id":"t9","timestamp":"","text":"","status":"ok","detail":"18 passed","offset":20,"end":30}""")
        val orphan = row("""{"role":"tool_result","id":"gone","timestamp":"","text":"","status":"ok","detail":"x","offset":30,"end":40}""")
        assertEquals(listOf("gradle test", "18 passed"), ChatSteps.of(listOf(call, result, orphan)).single().output)
    }

    @Test
    fun previewKeepsFourLinesAndBoundedLength() {
        assertEquals(listOf("one", "two", "three", "four"), ChatSteps.preview("one\ntwo\nthree\nfour\nfive"))
        assertEquals(320, ChatSteps.preview("x".repeat(400)).single().length)
        assertEquals(319, ChatSteps.preview("x".repeat(319) + "😀" + "x".repeat(50)).single().length)
    }

    @Test
    fun cacheKeepsStepsAcrossRestarts() {
        val cache = ChatHistoryCache(temporary.newFolder("cache"))
        cache.save("session", history(rows))
        val restored = cache.restore("session")!!
        assertEquals(history(rows).messages, restored.messages)
        assertEquals(ChatSteps.of(rows), ChatSteps.of(restored.rows))
        assertEquals(2, ChatSteps.of(restored.rows).size)
    }

    @Test
    fun realReaderSendsStepsBetweenMessages() {
        val home = temporary.newFolder("home")
        val transcript = File(home, ".claude/projects/-synthetic/synthetic.jsonl").apply { parentFile!!.mkdirs() }
        fun event(type: String, second: Long, message: JSONObject) = JSONObject().put("type", type)
            .put("timestamp", Instant.ofEpochSecond(1_700_000_000L + second).toString()).put("message", message).toString() + "\n"
        transcript.writeText(
            event("user", 0, JSONObject().put("id", "u1").put("role", "user").put("content", "check it")) +
                event("assistant", 1, JSONObject().put("id", "a1").put("role", "assistant").put("content", JSONArray()
                    .put(JSONObject().put("type", "text").put("text", "Looking."))
                    .put(JSONObject().put("type", "tool_use").put("id", "t1").put("name", "Bash")
                        .put("input", JSONObject().put("command", "ls -la").put("description", "List files"))))) +
                event("user", 2, JSONObject().put("role", "user").put("content", JSONArray()
                    .put(JSONObject().put("type", "tool_result").put("tool_use_id", "t1").put("content", "one\ntwo")))) +
                event("assistant", 3, JSONObject().put("id", "a3").put("role", "assistant").put("content", "Done.")),
        )
        ChatHistoryProcess("synthetic", "/synthetic", home).use { client ->
            val history = client.all()
            assertEquals(listOf("check it", "Looking.", "Done."), history.messages.map { it.text })
            val steps = ChatSteps.of(history.rows)
            assertEquals(listOf("Ran List files"), steps.map { it.text })
            assertEquals(listOf("one", "two"), steps.single().output.takeLast(2))
            val placement = ChatSteps.place(history.messages, steps)
            assertEquals(listOf(history.messages.last().key), placement.above.keys.toList())
        }
    }
}
