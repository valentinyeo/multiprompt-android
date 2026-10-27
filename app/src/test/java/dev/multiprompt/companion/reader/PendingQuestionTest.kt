package dev.multiprompt.companion.reader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingQuestionTest {
    private val payload = JSONObject("""{"provider":"claude","questions":[{"question":"Exact question?","header":"Route","multiSelect":true,"options":[{"label":"First","description":"Exact description"},{"label":"Second","description":""}]}]}""")
    private fun row(offset: Long, question: JSONObject? = null, result: String = "") =
        ChatHistoryProtocol.Row("assistant", "", "call", "", offset, offset + 1, questions = question?.let { request ->
            val questions = request.getJSONArray("questions")
            (0 until questions.length()).map { PendingQuestion.parse("call", it, questions.getJSONObject(it), request.getString("provider")) }
        }.orEmpty(), questionResult = result)

    @Test fun pendingPayloadIsExactAndHiddenFromOrdinaryMessages() {
        val history = ChatHistory(rows = listOf(row(0, payload)))
        val question = history.pendingQuestions.single()
        assertEquals("Exact question?", question.title)
        assertEquals("Route", question.header)
        assertEquals("Exact description", question.options.first().description)
        assertTrue(question.multiSelect)
        assertTrue(question.other)
        assertTrue(history.messages.isEmpty())
        assertEquals(question, ChatHistory(rows = listOf(ChatHistoryProtocol.parseRow(history.rows.single().json()))).pendingQuestions.single())
    }

    @Test fun resultsSettleOnlyTheirOwnRequestAcrossHistoryPages() {
        val pending = row(0, payload)
        assertTrue(ChatHistory(rows = listOf(pending, row(2, result = "call"))).pendingQuestions.isEmpty())
        assertEquals(1, ChatHistory(rows = listOf(pending, row(2, result = "another-call"))).pendingQuestions.size)
    }

    @Test fun matchingProseCannotEnableAQuestionWithoutTheLiveProviderPrompt() {
        val question = ChatHistory(rows = listOf(row(0, payload))).pendingQuestions.single()
        assertFalse(question.isVisible("Exact question?\n1. First\n2. Second"))
        assertTrue(question.isVisible("Exact\n question?\n❯ 1. First\n2. Second\nSpace to toggle"))
    }
    @Test fun codexOtherRespectsItsStructuredSettingAndFreeTextQuestions() {
        val value = payload.getJSONArray("questions").getJSONObject(0)
        assertFalse(PendingQuestion.parse("codex", 0, value, "codex").other)
        value.put("isOther", true)
        assertTrue(PendingQuestion.parse("codex", 0, value, "codex").other)
        value.remove("options")
        assertTrue(PendingQuestion.parse("codex", 0, value, "codex").other)
    }

    @Test fun olderCachesWithoutQuestionFactsAreRefetched() {
        val directory = java.nio.file.Files.createTempDirectory("question-cache").toFile()
        try {
            val cache = ChatHistoryCache(directory)
            cache.save("host/session", ChatHistory(sid = "sid", inode = 1, offset = 1, rows = listOf(row(0, payload))))
            val file = directory.listFiles()!!.single()
            val lines = file.readLines()
            file.writeText(JSONObject(lines.first()).put("version", 3).toString() + "\n" + lines.drop(1).joinToString("\n", postfix = "\n"))
            org.junit.Assert.assertNull(cache.restore("host/session"))
        } finally {
            directory.deleteRecursively()
        }
    }

}
