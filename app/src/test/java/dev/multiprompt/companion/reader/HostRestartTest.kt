package dev.multiprompt.companion.reader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRestartTest {
    private val sid = "6f353a80-d3df-4e44-88aa-edcc6c2b8999"

    private fun reply(body: String, id: Long = 1, protocol: Int = 1) =
        """{"id":$id,"message_protocol":$protocol,"result":$body}"""

    private fun status(value: String) = reply("""{"status":"$value"}""")

    @Test fun oneChatRequestIsTheDesktopShape() {
        val line = HostRestart.query(1, "claude", sid)
        assertTrue(line.endsWith("\n"))
        assertEquals(1, line.count { it == '\n' })
        val query = JSONObject(line)
        assertEquals(setOf("op", "id", "provider", "sid"), query.keys().asSequence().toSet())
        assertEquals("host_restart", query.getString("op"))
        assertEquals("claude", query.getString("provider"))
        assertEquals(sid, query.getString("sid"))
    }

    @Test fun allIdleRequestIsTheDesktopShape() {
        val line = HostRestart.queryAll(3)
        assertEquals(1, line.count { it == '\n' })
        val query = JSONObject(line)
        assertEquals(setOf("op", "id", "all"), query.keys().asSequence().toSet())
        assertEquals("host_restart", query.getString("op"))
        assertEquals(3, query.getInt("id"))
        assertTrue(query.getBoolean("all"))
    }

    @Test fun hostileIdsAndProvidersNeverReachTheRequest() {
        listOf("a b", "a\nb", "a\"b", "a;rm -rf /", "../x", "").forEach {
            assertFalse(it, HostRestart.supported("claude", it))
            assertThrows(IllegalArgumentException::class.java) { HostRestart.query(1, "claude", it) }
        }
        assertFalse(HostRestart.supported(null, sid))
        assertFalse(HostRestart.supported("kimi", sid))
        assertThrows(IllegalArgumentException::class.java) { HostRestart.query(1, "claude;ls", sid) }
        assertThrows(IllegalArgumentException::class.java) { HostRestart.query(0, "claude", sid) }
        assertThrows(IllegalArgumentException::class.java) { HostRestart.queryAll(0) }
    }

    @Test fun restartedAndRestartingAreSuccess() {
        assertEquals(HostRestart.Outcome.Restarted, HostRestart.outcome(status("restarted"), 1))
        assertEquals(HostRestart.Outcome.Restarted, HostRestart.outcome(status("restarting"), 1))
    }

    @Test fun aBusyChatWaitsInsteadOfFailing() {
        assertEquals(HostRestart.Outcome.Waiting, HostRestart.outcome(reply("""{"status":"waiting","reason":"chat_busy"}"""), 1))
    }

    @Test fun aChatWithoutALiveHostSaysSo() {
        val outcome = HostRestart.outcome(reply("""{"status":"already","reason":"not_hosted"}"""), 1)
        assertEquals(HostRestart.Outcome.Failed("Restart: this chat has no live host to restart"), outcome)
    }

    @Test fun refusedAndErrorFail() {
        listOf("refused", "error", "", "surprise").forEach {
            assertEquals(it, HostRestart.Outcome.Failed("Restart failed: this live chat is unavailable"), HostRestart.outcome(status(it), 1))
        }
    }

    @Test fun aMismatchedOrMissingEnvelopeIsNoAnswer() {
        val none = HostRestart.Outcome.Failed(HostRestart.NO_ANSWER)
        assertEquals(none, HostRestart.outcome(status("restarted").replace("\"id\":1", "\"id\":2"), 1))
        assertEquals(none, HostRestart.outcome(reply("""{"status":"restarted"}""", protocol = 2), 1))
        assertEquals(none, HostRestart.outcome("""{"id":1,"message_protocol":1}""", 1))
        assertEquals(none, HostRestart.outcome("not json", 1))
    }

    @Test fun countsReadTheReaderTally() {
        val counts = HostRestart.counts(reply("""{"status":"ok","restarted":2,"waiting":1,"skipped":4,"errors":1,"generation":120}"""), 1)
        assertEquals(HostRestart.Counts(restarted = 2, waiting = 1, errors = 1), counts)
    }

    @Test fun anUnusableAllReplyCountsAsUnanswered() {
        val none = HostRestart.Counts(unanswered = 1)
        assertEquals(none, HostRestart.counts(status("refused"), 1))
        assertEquals(none, HostRestart.counts(reply("""{"status":"ok","restarted":1}""", id = 2), 1))
        assertEquals(none, HostRestart.counts("", 1))
    }

    @Test fun countsAddAcrossServers() {
        val total = HostRestart.Counts(1, 2, 0, 0) + HostRestart.Counts(3, 0, 1, 1)
        assertEquals(HostRestart.Counts(4, 2, 1, 1), total)
    }

    @Test fun summaryNamesWhatHappened() {
        assertEquals("No idle chat to restart", HostRestart.summary(HostRestart.Counts()))
        assertEquals("Restarted 1 idle chat", HostRestart.summary(HostRestart.Counts(restarted = 1)))
        assertEquals(
            "Restarted 3 idle chats. 2 busy chats will restart when idle",
            HostRestart.summary(HostRestart.Counts(restarted = 3, waiting = 2)),
        )
        assertEquals(
            "1 busy chat will restart when idle. 1 chat could not restart. 2 servers did not answer",
            HostRestart.summary(HostRestart.Counts(waiting = 1, errors = 1, unanswered = 2)),
        )
        assertEquals("1 server did not answer", HostRestart.summary(HostRestart.Counts(unanswered = 1)))
    }
}
