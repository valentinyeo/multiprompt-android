package dev.multiprompt.companion.reader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshStartTest {
    private val sid = "6f353a80-d3df-4e44-88aa-edcc6c2b8999"

    private fun reply(status: String, id: Long = 1, protocol: Int = 1) =
        """{"id":$id,"message_protocol":$protocol,"result":{"status":"$status"}}"""

    @Test fun theRequestIsTheDesktopShape() {
        val line = FreshStart.query(1, "claude", sid)
        assertTrue(line.endsWith("\n"))
        assertEquals(1, line.count { it == '\n' })
        val query = JSONObject(line)
        assertEquals(setOf("op", "id", "provider", "sid"), query.keys().asSequence().toSet())
        assertEquals("fresh_start", query.getString("op"))
        assertEquals(1, query.getInt("id"))
        assertEquals("claude", query.getString("provider"))
        assertEquals(sid, query.getString("sid"))
    }

    @Test fun everyHostedProviderIsSupported() {
        listOf("claude", "codex", "pi", "hax", "cursor").forEach {
            assertTrue(it, FreshStart.supported(it, sid))
        }
    }

    @Test fun unknownProvidersAndEmptyIdsAreRefused() {
        assertFalse(FreshStart.supported(null, sid))
        assertFalse(FreshStart.supported("kimi", sid))
        assertFalse(FreshStart.supported("Claude", sid))
        assertFalse(FreshStart.supported("claude", ""))
    }

    @Test fun hostileIdsNeverReachTheRequest() {
        listOf("a b", "a\nb", "a\"b", "a;rm -rf /", "../x", "a'b", "a$(id)", "x".repeat(81)).forEach {
            assertFalse(it, FreshStart.supported("claude", it))
            assertThrows(IllegalArgumentException::class.java) { FreshStart.query(1, "claude", it) }
        }
    }

    @Test fun hostileProvidersNeverReachTheRequest() {
        listOf("claude;ls", "claude\n", "", "cl aude").forEach {
            assertThrows(IllegalArgumentException::class.java) { FreshStart.query(1, it, sid) }
        }
    }

    @Test fun aBadRequestIdIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { FreshStart.query(0, "claude", sid) }
    }

    @Test fun startedAndAlreadyAreSuccess() {
        assertEquals(FreshStart.Outcome.Started, FreshStart.outcome(reply("started"), 1))
        assertEquals(FreshStart.Outcome.Already, FreshStart.outcome(reply("already"), 1))
    }

    @Test fun busyAsksTheUserToWait() {
        val outcome = FreshStart.outcome(reply("busy"), 1)
        assertEquals(FreshStart.Outcome.Failed("Fresh start: wait for this reply to finish"), outcome)
    }

    @Test fun unavailableRefusedAndErrorFail() {
        listOf("unavailable", "refused", "error", "", "surprise").forEach {
            val outcome = FreshStart.outcome(reply(it), 1)
            assertEquals(it, FreshStart.Outcome.Failed("Fresh start failed: this live chat is unavailable"), outcome)
        }
    }

    @Test fun aMismatchedOrMissingEnvelopeIsNoAnswer() {
        val none = FreshStart.Outcome.Failed(FreshStart.NO_ANSWER)
        assertEquals(none, FreshStart.outcome(reply("started", id = 2), 1))
        assertEquals(none, FreshStart.outcome(reply("started", protocol = 2), 1))
        assertEquals(none, FreshStart.outcome("""{"id":1,"message_protocol":1}""", 1))
        assertEquals(none, FreshStart.outcome("not json", 1))
        assertEquals(none, FreshStart.outcome("", 1))
        assertEquals(none, FreshStart.outcome("""{"wire":"@mp\tx"}""", 1))
    }
}
