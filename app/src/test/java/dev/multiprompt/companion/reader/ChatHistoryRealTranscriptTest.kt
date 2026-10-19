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
            val terminalMessages = messages.count { message ->
                val source = sourceStrings(transcript, message.offset, message.end)
                terminal.findAll(message.text).any { match -> !source.contains(match.value, ignoreCase = true) }
            }
            println("Real transcript read: ${messages.size} messages, ${client.pages} pages, bytes 0..${history.offset}; " +
                "$regressions timestamp regressions; $terminalMessages messages with terminal markers missing from their source")
            assertTrue("Message timestamps must be non-decreasing: $regressions regressions", regressions == 0)
            assertTrue("Messages must not contain terminal markers absent from their source lines: $terminalMessages messages", terminalMessages == 0)
        }
    }

    private fun sourceStrings(file: File, start: Long, end: Long): String {
        val bytes = ByteArray((end - start).toInt())
        java.io.RandomAccessFile(file, "r").use { it.seek(start); it.readFully(bytes) }
        val out = StringBuilder()
        fun collect(value: Any?) {
            when (value) {
                is String -> out.append(value).append('\n')
                is JSONObject -> value.keys().forEach { collect(value.opt(it)) }
                is org.json.JSONArray -> for (i in 0 until value.length()) collect(value.opt(i))
            }
        }
        String(bytes, Charsets.UTF_8).lineSequence().filter(String::isNotBlank).forEach { line ->
            runCatching { JSONObject(line) }.getOrNull()?.let(::collect)
        }
        return out.toString()
    }
}
