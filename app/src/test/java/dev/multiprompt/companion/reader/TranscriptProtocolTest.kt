package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class TranscriptProtocolTest {
    private val tmux = TranscriptProtocol.Identity(AgentKind.CLAUDE, "claude-project", "/home/me/project")
    private val sid = tmux.copy(sid = "session-id")

    private fun row(role: String, text: String, timestamp: String = "-"): String =
        "M\t$role\t$timestamp\t${Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))}"

    private fun wire(rows: List<String>, identity: TranscriptProtocol.Identity = sid): String = buildString {
        append("@mp\t7\ttranscript\tok\tsession-id\t${rows.size}\t123.456")
        if (identity.sid == null) append("\t${identity.agent.name.lowercase()}\t${identity.tmuxName}")
        append('\n')
        rows.forEach { append(it).append('\n') }
        append("@@MP_END@@\n")
    }

    @Test fun requestsHaveSixFieldsAndUseResolvedSidAndToken() {
        assertEquals("7\ttranscript\tclaude\ttmux:claude-project\t/home/me/project\t-\n", TranscriptProtocol.request(7, tmux))
        assertEquals("8\ttranscript\tclaude\t123.456\t/home/me/project\tsession-id\n", TranscriptProtocol.request(8, sid, "123.456"))
        assertEquals("8\ttranscript\tcodex\ttmux:codex-test\t-\t-\n", TranscriptProtocol.request(8, TranscriptProtocol.Identity(AgentKind.CODEX, "codex-test")))
    }

    @Test fun requestsRejectControlCharactersAndUnsupportedAgents() {
        listOf(tmux.copy(tmuxName = "bad\nname"), tmux.copy(cwd = "/bad\tpath"), tmux.copy(sid = "bad\nsid"), tmux.copy(agent = AgentKind.HAX)).forEach {
            try {
                TranscriptProtocol.request(7, it)
                throw AssertionError("Invalid identity accepted")
            } catch (_: IllegalArgumentException) { }
        }
        AgentKind.entries.forEach { assertEquals(it in listOf(AgentKind.CLAUDE, AgentKind.CODEX), TranscriptProtocol.supports(it)) }
    }

    @Test fun headerResolvesIdentityAndRowsPreserveRolesOrderTimestampAndUnicode() {
        val rows = listOf(row("user", "Hello\n世界 👋"), row("assistant", "# Reply\n```kotlin\nval x = 1\n```", "2026-10-08T12:34:00+02:00"))
        val parsed = TranscriptProtocol.parse(wire(rows, tmux), 7, tmux)!!
        assertEquals("session-id", parsed.sid)
        assertEquals("123.456", parsed.token)
        assertEquals(listOf("user", "assistant"), parsed.messages.map { it.role })
        assertEquals("Hello\n世界 👋", parsed.messages[0].text)
        assertNull(parsed.messages[0].timestamp)
        assertEquals("2026-10-08T12:34:00+02:00", parsed.messages[1].timestamp)
        assertTrue(parsed.messages[1].text.contains("```kotlin"))
        val codex = tmux.copy(agent = AgentKind.CODEX)
        assertNotNull(TranscriptProtocol.parse(wire(rows, codex), 7, codex))
    }

    @Test fun wrongHeaderRequestSessionAgentOrTmuxIsRejected() {
        val good = wire(listOf(row("user", "Hi")), tmux)
        assertNotNull(TranscriptProtocol.parse(good, 7, tmux))
        assertNull(TranscriptProtocol.parse(good, 8, tmux))
        assertNull(TranscriptProtocol.parse(good, 7, tmux.copy(tmuxName = "other")))
        assertNull(TranscriptProtocol.parse(good, 7, tmux.copy(agent = AgentKind.CODEX)))
        assertNull(TranscriptProtocol.parse(wire(listOf(row("user", "Hi"))), 7, sid.copy(sid = "other")))
        listOf(good.replace("@mp", "@other"), good.replace("\ttranscript\t", "\tping\t"), good.replace("\tok\t", "\terror\t"), good.replace("123.456", "bad-token"), good.replace("session-id", "../path"), good.replace("\t1\t", "\t-1\t")).forEach {
            assertNull(TranscriptProtocol.parse(it, 7, tmux))
        }
    }

    @Test fun sameRepliesHaveNoRowsAndCarryTheObservedToken() {
        val same = "@mp\t7\ttranscript\tsame\tsession-id\t0\t123.456\n@@MP_END@@\n"
        val parsed = TranscriptProtocol.parse(same, 7, sid)!!
        assertEquals("same", parsed.status)
        assertEquals("123.456", parsed.token)
        assertTrue(parsed.messages.isEmpty())
        assertNull(TranscriptProtocol.parse(same.replace("\t0\t", "\t1\t"), 7, sid))
        assertNull(TranscriptProtocol.parse(same.replace("@@MP_END@@", row("user", "Hi") + "\n@@MP_END@@"), 7, sid))
        assertNotNull(TranscriptProtocol.parse(same.replace("\t123.456", ""), 7, sid))
    }

    @Test fun missingTranscriptIsAFramedFallbackNotAnEmptyChat() {
        val missing = "@mp\t7\ttranscript\tmissing\t-\t0\t-\tclaude\tclaude-project\n@@MP_END@@\n"
        assertEquals("missing", TranscriptProtocol.parse(missing, 7, tmux)!!.status)
        assertNull(TranscriptProtocol.parse(missing.replace("\tmissing\t", "\tok\t"), 7, tmux))
    }

    @Test fun rowsRejectBadRoleEncodingUtf8AndFieldCounts() {
        listOf("M\tsystem\t-\tSGk=", "X\tuser\t-\tSGk=", "M\tuser\t-\t!!!", "M\tuser\t-\tSGk", "M\tuser\t-\t/w==", "M\tuser\t-\t", "M\tuser\t-\tSGk=\textra", "M\tuser\tSGk=").forEach {
            assertNull("Accepted $it", TranscriptProtocol.parse(wire(listOf(it)), 7, sid))
        }
    }

    @Test fun endSentinelAndDeclaredRowCountAreMandatory() {
        val good = wire(listOf(row("user", "Hi")))
        assertNull(TranscriptProtocol.parse(good.replace("@@MP_END@@\n", ""), 7, sid))
        assertNull(TranscriptProtocol.parse(good + "extra\n", 7, sid))
        assertNull(TranscriptProtocol.parse(good.replace("@@MP_END@@", "@@MP_END@@\n@@MP_END@@"), 7, sid))
        assertNull(TranscriptProtocol.parse(good.replace("\t1\t", "\t2\t"), 7, sid))
        assertNotNull(TranscriptProtocol.parse(good.replace("\n", "\r\n") + "\n", 7, sid))
    }

    @Test fun messageWindowAccepts96ButRejects97WithoutTruncating() {
        val rows = (0 until 96).map { row(if (it % 2 == 0) "user" else "assistant", "Message $it") }
        val messages = TranscriptProtocol.parse(wire(rows), 7, sid)!!.messages
        assertEquals(96, messages.size)
        assertEquals("Message 0", messages.first().text)
        assertEquals("Message 95", messages.last().text)
        assertNull(TranscriptProtocol.parse(wire(rows + row("user", "97th")), 7, sid))
    }

    @Test fun totalDecodedBytesAreBoundedAcrossMessages() {
        val rows = listOf(row("user", "x".repeat(90_000)), row("assistant", "y".repeat(90_000)))
        assertNotNull(TranscriptProtocol.parse(wire(rows), 7, sid))
        assertNull(TranscriptProtocol.parse(wire(rows + row("user", "z")), 7, sid))
        assertNull(TranscriptProtocol.parse(wire(listOf(row("user", "x".repeat(180_001)))), 7, sid))
    }

    @Test fun onlyUserRowsStartingWithTaskNotificationAreHidden() {
        val rows = listOf(row("user", "<task-notification>hidden"), row("assistant", "<task-notification>visible"), row("user", "Mention <task-notification>"), row("user", "Actual prompt"), row("user", "  \n<task-notification>hidden too"))
        val messages = TranscriptProtocol.parse(wire(rows), 7, sid)!!.messages
        assertEquals(listOf("<task-notification>visible", "Mention <task-notification>", "Actual prompt"), messages.map { it.text })
        assertTrue(TranscriptProtocol.parse(wire(rows.take(1)), 7, sid)!!.messages.isEmpty())
    }

    @Test fun framerHandlesSplitSentinelsUnicodeAndRepeatedResponses() {
        val first = wire(listOf(row("user", "世界")), tmux.copy(tmuxName = "claude-世界"))
        val second = wire(listOf(row("assistant", "Reply")))
        val framer = TranscriptProtocol.Framer()
        val frames = (first + second).toByteArray(Charsets.UTF_8).flatMap { framer.feed(byteArrayOf(it)) }
        assertEquals(listOf(first, second), frames)
        assertFalse(framer.hasPending())
        val partial = TranscriptProtocol.Framer()
        assertTrue(partial.feed(first.substringBefore("@@MP_END@@").toByteArray()).isEmpty())
        assertTrue(partial.hasPending())
        assertEquals(listOf(first), partial.feed("@@MP_END@@\n".toByteArray()))
    }

    @Test fun framerHandlesCrLfAndDoesNotTreatEmbeddedSentinelTextAsAnEnd() {
        val good = wire(listOf(row("user", "Hi"))).replace("\n", "\r\n")
        assertEquals(listOf(good), TranscriptProtocol.Framer().feed(good.toByteArray()))
        val framer = TranscriptProtocol.Framer()
        assertTrue(framer.feed("prefix@@MP_END@@\n".toByteArray()).isEmpty())
        assertTrue(framer.hasPending())
    }

    @Test(expected = java.nio.charset.CharacterCodingException::class)
    fun framerRejectsInvalidUtf8RatherThanReplacingIt() {
        TranscriptProtocol.Framer().feed(byteArrayOf(0xff.toByte()) + "\n@@MP_END@@\n".toByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun framerRejectsOversizeMultilineResponses() {
        TranscriptProtocol.Framer().feed("123456789012\n".repeat(20_001).toByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun framerRejectsUnterminatedOversizeResponses() {
        TranscriptProtocol.Framer().feed(ByteArray(TranscriptProtocol.MAX_WIRE_BYTES + 1) { 'x'.code.toByte() })
    }
}
