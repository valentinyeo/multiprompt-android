package dev.multiprompt.companion.reader

import java.io.File
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ChatHistoryRealTranscriptTest {
    @Test
    fun fullPersonalTranscriptHasUniqueOrderedMessagesAndNoTerminalCaptures() {
        val path = System.getenv("MP_REAL_TRANSCRIPT")
        assumeTrue(!path.isNullOrBlank())
        val transcript = File(requireNotNull(path))
        assertTrue(transcript.isFile)
        val sid = transcript.nameWithoutExtension
        val firstUser = transcript.useLines { lines ->
            lines.map { JSONObject(it) }.first { event ->
                event.optString("type") == "user" && !event.optBoolean("isMeta") && !event.optBoolean("isSidechain") &&
                    event.optJSONObject("message")?.opt("content") is String
            }.getJSONObject("message").getString("content")
        }
        ChatHistoryProcess(sid, "/home/valentin/projects").use { client ->
            val history = client.all()
            val messages = history.messages
            assertTrue(messages.size > 500)
            assertEquals(0L, history.start)
            assertTrue("First user message must match source", firstUser == messages.first().text)
            assertEquals(messages.size, messages.map { it.key }.toSet().size)
            assertTrue(messages.zipWithNext().all { (a, b) -> a.offset < b.offset })
            val timestamps = messages.mapNotNull { it.timestamp.takeIf(String::isNotBlank)?.let(Instant::parse) }
            val regressions = timestamps.zipWithNext().count { (a, b) -> a > b }
            val terminal = Regex("Update installed|Restart to apply|esc to interrupt|⏵⏵|────", RegexOption.IGNORE_CASE)
            val terminalMessages = messages.count { terminal.containsMatchIn(it.text) }
            println("Real transcript read: ${messages.size} messages, ${client.pages} pages, bytes 0..${history.offset}; " +
                "$regressions timestamp regressions; $terminalMessages source messages with terminal markers")
            assertTrue("Message timestamps must be non-decreasing: $regressions regressions", regressions == 0)
            assertTrue("Source conversation must not contain terminal markers: $terminalMessages messages", terminalMessages == 0)
        }
    }
}
