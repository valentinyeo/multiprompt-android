package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.model.TmuxSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRowPartsTest {
    private val question = JSONObject("""{"id":"call","questions":[{"question":"Ship **it**?","options":[{"label":"Yes"}]}]}""")
    private val session = TmuxSession("host", "live-1", 1, 0, 100, replyAtEpochSeconds = 90, replyWaiting = true)

    private fun page(status: JSONObject?, reply: String = "Done.\nMore detail."): ChatHistoryProtocol.Page {
        val row = JSONObject("""{"role":"assistant","text":${JSONObject.quote(reply)},"id":"row-0","offset":0,"end":1}""")
        val wire = "@mp\t1\thistory\tok\tsid\t0\t10\t1\t0\tclaude\t-\t1\t${"a".repeat(64)}\ttail\t0\n$row\n" +
            (status?.let { "$it\n" } ?: "") + "${TranscriptProtocol.END}\n"
        return ChatHistoryProtocol.parse(JSONObject().put("id", 1).put("wire", wire).toString(), 1,
            ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "", sid = "sid"))!!
    }

    private fun status(todos: String = "[]", question: Any = JSONObject.NULL) =
        JSONObject("""{"role":"status","todos":$todos}""").put("question", question)

    @Test
    fun currentStepIsTheInProgressTodo() {
        val todos = """[{"text":"Read logs","status":"completed"},{"text":" Fix the bug ","status":"in_progress"},{"text":"Ship","status":"pending"}]"""
        assertEquals("Fix the bug", page(status(todos)).step)
        assertEquals("Fix the bug", ChatHistory().apply(page(status(todos))).step)
    }

    @Test
    fun noInProgressTodoMeansNoStep() {
        assertEquals("", page(status("""[{"text":"Ship","status":"pending"}]""")).step)
        assertEquals("", page(null).step)
        assertEquals("", page(JSONObject("""{"role":"status"}""")).step)
    }

    @Test
    fun partsCarryQuestionStepAndLastReplyWithTheSessionStamps() {
        val history = ChatHistory().apply(page(status("""[{"text":"Fix the bug","status":"in_progress"}]""", question)))
        val parts = ChatRowParts.from(session, history)
        assertEquals("Ship it?", parts.question)
        assertEquals("Fix the bug", parts.step)
        assertEquals("Done.", parts.reply)
        assertEquals(100, parts.activityEpochSeconds)
        assertEquals(90, parts.replyEpochSeconds)
        assertTrue(parts.isCurrentFor(session))
    }

    @Test
    fun partsGoStaleWhenTheChatMoves() {
        val parts = ChatRowParts.from(session, ChatHistory().apply(page(null)))
        assertTrue(parts.isCurrentFor(session))
        assertEquals(false, parts.isCurrentFor(session.copy(lastActivityEpochSeconds = 101)))
        assertEquals(false, parts.isCurrentFor(session.copy(replyAtEpochSeconds = 95)))
    }

    @Test
    fun firstLineStripsMarkdownAndCaps() {
        assertEquals("Heading text", ChatRowParts.firstLine("\n## **Heading** `text`\nsecond"))
        assertEquals("quoted item", ChatRowParts.firstLine("> - quoted   item"))
        assertEquals("", ChatRowParts.firstLine("  \n "))
        assertEquals(200, ChatRowParts.firstLine("a".repeat(500)).length)
    }
}
