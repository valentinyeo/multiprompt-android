package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QuestionSnapshotTest {
    private val question = JSONObject("""{"id":"call","questions":[{"question":"Choose?","header":"Header","multiSelect":true,"options":[{"label":"First","description":"Detail"}]}]}""")

    private fun page(status: JSONObject?, mode: String = "tail", agent: AgentKind = AgentKind.CLAUDE): ChatHistoryProtocol.Page {
        val base = if (mode == "delta") 10 else 0
        val end = if (mode == "delta") 20 else 10
        val oldest = if (mode == "delta") 0 else base
        val wireAgent = if (agent == AgentKind.CODEX) "codex" else "claude"
        val row = JSONObject("""{"role":"assistant","text":"History","id":"row-$base","offset":$base,"end":${base + 1}}""")
        val wire = "@mp\t1\thistory\tok\tsid\t$base\t$end\t1\t0\t$wireAgent\t-\t1\t${"a".repeat(64)}\t$mode\t$oldest\n$row\n" +
            (status?.let { "$it\n" } ?: "") + "${TranscriptProtocol.END}\n"
        return ChatHistoryProtocol.parse(JSONObject().put("id", 1).put("wire", wire).toString(), 1,
            ChatHistoryProtocol.Identity(agent, "", sid = "sid"))!!
    }

    private fun status(value: Any) = JSONObject().put("role", "status").put("question", value)

    @Test fun snapshotReplacesAndClearsAcrossPages() {
        val initial = ChatHistory().apply(page(status(question)))
        val pending = initial.pendingQuestions.single()
        assertEquals("call", pending.callId)
        assertEquals(0, pending.index)
        assertEquals("Header", pending.header)
        assertEquals("Detail", pending.options.single().description)
        assertTrue(pending.multiSelect)
        assertTrue(pending.isVisible("Choose?\n❯ 1. First\nSpace to toggle"))
        val cleared = initial.apply(page(status(JSONObject.NULL), "delta"))
        assertTrue(cleared.pendingQuestions.isEmpty())
        assertEquals(2, cleared.messages.size)
        assertTrue(initial.apply(page(JSONObject().put("role", "status"), "delta")).pendingQuestions.isEmpty())
        assertTrue(initial.apply(page(null, "delta")).pendingQuestions.isEmpty())
        val replacement = JSONObject(question.toString()).put("id", "new-call")
        assertEquals("new-call", initial.apply(page(status(replacement), "delta")).pendingQuestions.single().callId)
        val archived = ChatHistory(sid = "sid", inode = 1, offset = 20, start = 10).apply(page(status(question), "older"))
        assertEquals(pending, archived.pendingQuestions.single())
        assertEquals(20, archived.offset)
    }

    @Test fun codexFreeTextAndGroupedIndexes() {
        val value = JSONObject("""{"id":"codex-call","provider":"codex","questions":[{"question":"First?"},{"question":"Second?","header":"H"}]}""")
        val questions = page(status(value), agent = AgentKind.CODEX).pendingQuestions
        assertEquals(listOf(0, 1), questions.map { it.index })
        assertEquals(listOf("codex-call", "codex-call"), questions.map { it.callId })
        assertTrue(questions.all { it.options.isEmpty() && it.other && it.provider == "codex" })
    }

    @Test fun malformedMetadataNeverDiscardsChat() {
        val bad = listOf(
            """{"id":"","questions":[]}""",
            """{"id":"c","questions":[{"question":" ","options":[{"label":"a"}]}]}""",
            """{"id":"c","questions":[{"question":"Q","header":2,"options":[{"label":"a"}]}]}""",
            """{"id":"c","questions":[{"question":"Q","multiSelect":"true","options":[{"label":"a"}]}]}""",
            """{"id":"c","questions":[{"question":"Q","options":[{"label":" "}]}]}""",
            """{"id":"c","questions":[{"question":"Q","options":[{"label":"a","description":5}]}]}""",
            """{"id":"c","questions":[{"question":"Q","options":[]}]}""",
            """{"id":"c","questions":[{"question":"Q","options":"bad"}]}""",
            """{"id":"c","questions":[{"question":"Q","options":["bad"]}]}""",
            """{"id":"c","provider":"codex","questions":[{"question":"Q"}]}"""
        )
        for (value in bad) {
            val history = ChatHistory().apply(page(status(JSONObject(value))))
            assertTrue(value, history.pendingQuestions.isEmpty())
            assertEquals("History", history.messages.single().text)
        }
    }
}
