package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.model.TmuxSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartNewestTest {
    private val sidA = "6f353a80-d3df-4e44-88aa-edcc6c2b8999"
    private val sidB = "0a1b2c3d-d3df-4e44-88aa-edcc6c2b8999"
    private val sidC = "11223344-d3df-4e44-88aa-edcc6c2b8999"
    private val sidD = "55667788-d3df-4e44-88aa-edcc6c2b8999"

    private fun reply(status: String, id: Long = 1, protocol: Int = 1) =
        """{"id":$id,"message_protocol":$protocol,"result":{"status":"$status","generation":109}}"""

    private fun hostedSession(
        hostId: String = "host-1",
        name: String = "live-alpha",
        sid: String = sidA,
        provider: String = "claude",
        replyWaiting: Boolean? = true,
        preview: String = "",
        paneCommand: String = "sleep",
    ) = TmuxSession(
        hostId = hostId,
        name = name,
        windows = 1,
        attachedClients = 0,
        lastActivityEpochSeconds = 1000L,
        preview = preview,
        paneCommand = paneCommand,
        hostSessionId = sid,
        replyWaiting = replyWaiting,
        hostProvider = provider,
    )

    @Test fun theRequestIsTheDesktopShape() {
        val withTmux = RestartNewest.query(1, "claude", sidA, "live-alpha")
        assertTrue(withTmux.endsWith("\n"))
        assertEquals(1, withTmux.count { it == '\n' })
        val json = JSONObject(withTmux)
        assertEquals(setOf("op", "id", "provider", "sid", "tmux"), json.keys().asSequence().toSet())
        assertEquals("host_restart", json.getString("op"))
        assertEquals(1, json.getInt("id"))
        assertEquals("claude", json.getString("provider"))
        assertEquals(sidA, json.getString("sid"))
        assertEquals("live-alpha", json.getString("tmux"))

        val withoutTmux = JSONObject(RestartNewest.query(2, "cursor", sidB))
        assertEquals(setOf("op", "id", "provider", "sid"), withoutTmux.keys().asSequence().toSet())
        assertEquals("cursor", withoutTmux.getString("provider"))
        assertEquals(sidB, withoutTmux.getString("sid"))
    }

    @Test fun everyHostedProviderIsSupported() {
        listOf("claude", "codex", "pi", "hax", "cursor").forEach {
            assertTrue(it, RestartNewest.supported(it, sidA))
        }
    }

    @Test fun unknownProvidersAndEmptyIdsAreRefused() {
        assertFalse(RestartNewest.supported(null, sidA))
        assertFalse(RestartNewest.supported("kimi", sidA))
        assertFalse(RestartNewest.supported("Claude", sidA))
        assertFalse(RestartNewest.supported("claude", ""))
    }

    @Test fun hostileInputsNeverReachTheRequest() {
        listOf("a b", "a\nb", "a\"b", "a;rm -rf /", "../x", "a'b", "a$(id)", "x".repeat(81)).forEach {
            assertFalse(it, RestartNewest.supported("claude", it))
            assertThrows(IllegalArgumentException::class.java) { RestartNewest.query(1, "claude", it, "live-a") }
        }
        listOf("claude;ls", "claude\n", "", "cl aude").forEach {
            assertThrows(IllegalArgumentException::class.java) { RestartNewest.query(1, it, sidA, "live-a") }
        }
        listOf("live-a;b", "live a", "live\na", "a'b", "x".repeat(129)).forEach {
            assertThrows(IllegalArgumentException::class.java) { RestartNewest.query(1, "claude", sidA, it) }
        }
        assertThrows(IllegalArgumentException::class.java) { RestartNewest.query(0, "claude", sidA, "live-a") }
    }

    @Test fun outcomesMatchDesktopStatuses() {
        assertEquals(RestartNewest.Outcome.Restarted, RestartNewest.outcome(reply("restarted"), 1))
        assertEquals(RestartNewest.Outcome.Restarting, RestartNewest.outcome(reply("restarting"), 1))
        assertEquals(RestartNewest.Outcome.Waiting, RestartNewest.outcome(reply("waiting"), 1))
        listOf("already", "refused", "error").forEach {
            assertEquals(it, RestartNewest.Outcome.Failed(RestartNewest.NOT_HOSTED), RestartNewest.outcome(reply(it), 1))
        }
        val none = RestartNewest.Outcome.Failed(RestartNewest.NO_ANSWER)
        assertEquals(none, RestartNewest.outcome(reply("surprise"), 1))
        assertEquals(none, RestartNewest.outcome(reply("restarted", id = 2), 1))
        assertEquals(none, RestartNewest.outcome(reply("restarted", protocol = 2), 1))
        assertEquals(none, RestartNewest.outcome("""{"id":1,"message_protocol":1}""", 1))
        assertEquals(none, RestartNewest.outcome("not json", 1))
        assertEquals(none, RestartNewest.outcome("", 1))
    }

    @Test fun noticesMatchDesktopAndBatchResults() {
        assertEquals("Chat restarted on the newest version", RestartNewest.notice(RestartNewest.Outcome.Restarted))
        assertEquals("Chat is restarting on the newest version", RestartNewest.notice(RestartNewest.Outcome.Restarting))
        assertEquals("Chat restarts when this reply ends", RestartNewest.notice(RestartNewest.Outcome.Waiting))
        assertEquals(RestartNewest.NOT_HOSTED, RestartNewest.notice(RestartNewest.Outcome.Failed(RestartNewest.NOT_HOSTED)))

        assertEquals("No idle chats to restart", RestartNewest.batchNotice(RestartNewest.BatchResult()))
        assertEquals(
            "Restarted 1 idle chat on the newest version",
            RestartNewest.batchNotice(RestartNewest.BatchResult(restarted = 1)),
        )
        assertEquals(
            "Restarted 3 idle chats on the newest version",
            RestartNewest.batchNotice(RestartNewest.BatchResult(restarted = 2, restarting = 1)),
        )
        assertEquals(
            "Restarted 2 idle chats; 1 failed",
            RestartNewest.batchNotice(RestartNewest.BatchResult(restarted = 2, failed = 1)),
        )
        assertEquals(
            "Idle chats will restart when their replies end",
            RestartNewest.batchNotice(RestartNewest.BatchResult(waiting = 1)),
        )
        assertEquals(
            RestartNewest.NO_ANSWER,
            RestartNewest.batchNotice(RestartNewest.BatchResult(failed = 2)),
        )
    }

    @Test fun idleOnlyListSelectsEveryIdleHostedChat() {
        val sessions = listOf(
            hostedSession(hostId = "h1", name = "live-one", sid = sidA, provider = "claude", replyWaiting = true),
            hostedSession(hostId = "h1", name = "live-codex_two", sid = sidB, provider = "codex", replyWaiting = true),
            hostedSession(hostId = "h2", name = "live-pi_three", sid = sidC, provider = "pi", replyWaiting = true),
        )
        assertEquals(
            listOf(
                RestartNewest.Target("h1", "claude", sidA, "live-one"),
                RestartNewest.Target("h1", "codex", sidB, "live-codex_two"),
                RestartNewest.Target("h2", "pi", sidC, "live-pi_three"),
            ),
            RestartNewest.selectIdle(sessions),
        )
    }

    @Test fun workingChatsAreSkipped() {
        val inflightWorking = hostedSession(hostId = "h1", name = "live-busy", sid = sidA, replyWaiting = false)
        val liveReaderWorking = hostedSession(hostId = "h1", name = "live-open", sid = sidB, replyWaiting = true)
        val unknownPreview = hostedSession(hostId = "h1", name = "live-unknown", sid = sidC, replyWaiting = null, preview = "Working...")
        val emptyPreview = hostedSession(hostId = "h1", name = "live-empty", sid = sidD, replyWaiting = null, preview = "")

        val workingKeys = setOf(RestartNewest.sessionKey("h1", "live-open"))
        assertFalse(RestartNewest.isIdle(inflightWorking, workingKeys))
        assertFalse(RestartNewest.isIdle(liveReaderWorking, workingKeys))
        assertFalse(RestartNewest.isIdle(unknownPreview, workingKeys))
        assertFalse(RestartNewest.isIdle(emptyPreview, workingKeys))
        assertEquals(
            emptyList<RestartNewest.Target>(),
            RestartNewest.selectIdle(listOf(inflightWorking, liveReaderWorking, unknownPreview, emptyPreview), workingKeys),
        )
    }

    @Test fun mixedListSelectsOnlyUniqueIdleHostedChats() {
        val idleOne = hostedSession(hostId = "h1", name = "live-idle-1", sid = sidA, provider = "claude", replyWaiting = true)
        val workingInflight = hostedSession(hostId = "h1", name = "live-working", sid = sidB, provider = "claude", replyWaiting = false)
        val workingReader = hostedSession(hostId = "h1", name = "live-streaming", sid = sidC, provider = "codex", replyWaiting = true)
        val duplicateIdle = hostedSession(hostId = "h1", name = "live-idle-1-alias", sid = sidA, provider = "claude", replyWaiting = true)
        val idlePromptFallback = hostedSession(
            hostId = "h2",
            name = "live-pi_idle",
            sid = sidD,
            provider = "pi",
            replyWaiting = null,
            preview = "Ready\n❯ ",
        )
        val nonHostedClaude = TmuxSession("h1", "cl-plain", 1, 0, 1000L, paneCommand = "claude", replyWaiting = true)
        val plainShell = TmuxSession("h1", "shell-1", 1, 0, 1000L, paneCommand = "bash", replyWaiting = true)
        val kimiChat = TmuxSession("h1", "kimi-1", 1, 0, 1000L, paneCommand = "kimi", hostSessionId = sidB, replyWaiting = true)

        assertNull(RestartNewest.target(nonHostedClaude))
        assertNull(RestartNewest.target(plainShell))
        assertNull(RestartNewest.target(kimiChat))
        assertEquals(AgentKind.KIMI, kimiChat.agent)

        val workingKeys = setOf(RestartNewest.sessionKey("h1", "live-streaming"))
        val selected = RestartNewest.selectIdle(
            listOf(
                idleOne,
                workingInflight,
                workingReader,
                duplicateIdle,
                nonHostedClaude,
                plainShell,
                kimiChat,
                idlePromptFallback,
            ),
            workingKeys,
        )
        assertEquals(
            listOf(
                RestartNewest.Target("h1", "claude", sidA, "live-idle-1"),
                RestartNewest.Target("h2", "pi", sidD, "live-pi_idle"),
            ),
            selected,
        )
    }

    @Test fun emptyListSelectsNothing() {
        assertEquals(emptyList<RestartNewest.Target>(), RestartNewest.selectIdle(emptyList()))
        assertEquals(
            emptyList<RestartNewest.Target>(),
            RestartNewest.selectIdle(emptyList(), setOf("h1::live-a")),
        )
    }
}
