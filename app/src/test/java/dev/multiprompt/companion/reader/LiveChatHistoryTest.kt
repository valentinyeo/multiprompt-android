package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveChatHistoryTest {
    private val sid = "721fb507-2781-4e48-8b6d-debd6e2da3f3"
    private val probe = "015ec783fa4c86ccac5a8f5a4b9e777a22acc6b901f04c1efe5c0766087a5bff"
    private val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "live-1517b1", "/tmp", sid)

    private fun reply(id: Long, header: String, body: String = "") = JSONObject().put("id", id)
        .put("wire", header + "\n" + (if (body.isEmpty()) "" else body + "\n") + TranscriptProtocol.END + "\n").toString()

    private fun emptyHeader(id: Long) = "@mp\t$id\thistory\tok\t$sid\t0\t0\t0\t0\tclaude\tlive-1517b1\t0\t$probe\tdelta\t0"

    private fun page(id: Long, header: String, body: String = "") =
        ChatHistoryProtocol.parse(reply(id, header, body), id, identity)

    private fun transcriptHeader(id: Long) = "@mp\t$id\thistory\tok\t$sid\t0\t20\t1278069\t0\tclaude\tlive-1517b1\t1793653993390199707\t$probe\ttail\t0"

    private fun row(offset: Long, role: String, text: String) = JSONObject()
        .put("role", role).put("text", text).put("id", "row-$offset").put("offset", offset).put("end", offset + 10).toString()

    @Test
    fun requestCarriesTheHostSessionSoTheReaderCanFindItsTranscript() {
        val request = JSONObject(ChatHistoryProtocol.request(1, identity, ChatHistory()))
        assertEquals(sid, request.getString("sid"))
        assertEquals("live-1517b1", request.getString("tmux"))
        assertTrue(request.getBoolean("follow"))
    }

    @Test
    fun aLiveChatWithoutATranscriptLoadsAsAnEmptyChat() {
        val loaded = ChatHistory().apply(page(1, emptyHeader(1), """{"role":"status","no_transcript":true}""")!!)
        assertEquals(sid, loaded.sid)
        assertTrue(loaded.messages.isEmpty())
        assertFalse(loaded.more)
    }

    @Test
    fun anEmptyLiveChatKeepsLoadingUntilTheFirstMessage() {
        val first = ChatHistory().apply(page(1, emptyHeader(1))!!)
        assertEquals(sid, JSONObject(ChatHistoryProtocol.request(2, identity, first)).getString("cached_sid"))
        val second = first.apply(page(2, emptyHeader(2))!!)
        assertTrue(second.messages.isEmpty())
        val loaded = second.apply(page(3, transcriptHeader(3),
            listOf(row(0, "user", "hi from the desktop"), row(10, "assistant", "pong")).joinToString("\n"))!!)
        assertEquals(listOf("hi from the desktop", "pong"), loaded.messages.map { it.text })
        assertEquals(sid, loaded.sid)
        assertEquals(20L, loaded.offset)
    }

    @Test
    fun anEmptyHeaderWithTranscriptOffsetsIsStillRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            page(1, "@mp\t1\thistory\tok\t$sid\t0\t40\t0\t0\tclaude\tlive-1517b1\t0\t$probe\tdelta\t0")
        }
    }

    @Test
    fun aLiveChatWhoseTranscriptIsMissingIsStillAnError() {
        assertNull(page(1, "@mp\t1\thistory\tmissing\t-\t0\tsession transcript not found"))
        assertNotNull(page(1, emptyHeader(1)))
    }
}
