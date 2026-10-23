package dev.multiprompt.companion.reader

import java.io.File
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatHistoryCorruptLineTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun event(index: Int, role: String, text: String): String = JSONObject()
        .put("type", role).put("uuid", "event-$index-${text.hashCode()}")
        .put("timestamp", Instant.ofEpochSecond(1_700_000_000L + index).toString())
        .put("message", JSONObject().put("id", "message-$index").put("role", role).put("content", text)).toString() + "\n"

    private fun role(index: Int) = if (index % 2 == 0) "user" else "assistant"

    private fun corrupt(index: Int): String =
        event(index, "assistant", "Interleaved head " + "x".repeat(80)).take(120) +
            event(index + 1, "user", "Interleaved full " + "y".repeat(80))

    private fun transcript(name: String): Pair<File, File> {
        val home = temporary.newFolder(name)
        return home to File(home, ".claude/projects/-synthetic/synthetic.jsonl").apply { parentFile!!.mkdirs() }
    }

    @Test
    fun corruptLineInTheTailYieldsNothingAndEveryValidMessageStays() {
        val (home, transcript) = transcript("tail")
        val expected = ArrayList<String>()
        transcript.bufferedWriter().use { writer ->
            for (index in 0 until 10) {
                expected.add("Before $index € ✔")
                writer.write(event(index, role(index), expected.last()))
            }
            writer.write(corrupt(100))
            for (index in 10 until 20) {
                expected.add("After $index € ✔")
                writer.write(event(index, role(index), expected.last()))
            }
        }
        ChatHistoryProcess("synthetic", "/synthetic", home).use { client ->
            assertEquals(expected, client.all().messages.map { it.text })
        }
    }

    @Test
    fun corruptLineInALargeTranscriptTailKeepsPagingFromTailToFirst() {
        val (home, transcript) = transcript("large")
        val expected = ArrayList<String>()
        transcript.bufferedWriter().use { writer ->
            for (index in 0 until 720) {
                if (index == 600) writer.write(corrupt(5000))
                expected.add("Message $index € ✔ " + "body ".repeat(260))
                writer.write(event(index, role(index), expected.last()))
            }
        }
        ChatHistoryProcess("synthetic", "/synthetic", home).use { client ->
            assertEquals(expected, client.all().messages.map { it.text })
            assertTrue(client.pages > 1)
        }
    }

    @Test
    fun streamedCatchUpSurvivesACorruptLineFollowedByNewMessages() {
        val (home, transcript) = transcript("streamed")
        val expected = ArrayList<String>()
        for (index in 0 until 10) {
            expected.add("Before $index € ✔")
            transcript.appendText(event(index, role(index), expected.last()))
        }
        ChatHistoryProcess("synthetic", "/synthetic", home).use { client ->
            assertEquals(expected, client.all().messages.map { it.text })
            transcript.appendText(corrupt(100))
            for (index in 10 until 16) {
                expected.add("New $index € ✔")
                transcript.appendText(event(index, role(index), expected.last()))
            }
            client.poll()
            while (client.history.more) client.poll()
            assertEquals(expected, client.history.messages.map { it.text })
        }
    }

    @Test
    fun multiPageDeltaCatchUpSurvivesACorruptLineInTheMiddle() {
        val (home, transcript) = transcript("delta")
        val expected = ArrayList<String>()
        for (index in 0 until 10) {
            expected.add("Before $index € ✔")
            transcript.appendText(event(index, role(index), expected.last()))
        }
        ChatHistoryProcess("synthetic", "/synthetic", home).use { client ->
            assertEquals(expected, client.all().messages.map { it.text })
            val pagesBefore = client.pages
            transcript.appendText(buildString {
                for (index in 10 until 810) {
                    if (index == 400) append(corrupt(5000))
                    val text = "Catch-up $index " + "€ ✔ body ".repeat(350)
                    append(event(index, role(index), text))
                    expected.add(text)
                }
            })
            client.poll()
            assertTrue(client.history.more)
            while (client.history.more) client.poll()
            assertTrue(client.pages - pagesBefore > 2)
            assertEquals(expected, client.history.messages.map { it.text })
        }
    }
}
