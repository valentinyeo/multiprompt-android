package dev.multiprompt.companion

import dev.multiprompt.companion.model.AgentHarness
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.model.CreatedSession
import dev.multiprompt.companion.model.createdSession
import dev.multiprompt.companion.reader.ChatHistory
import dev.multiprompt.companion.reader.ChatHistoryProtocol
import dev.multiprompt.companion.reader.MessageProtocol
import dev.multiprompt.companion.reader.TranscriptProtocol
import dev.multiprompt.companion.ssh.TmuxCommands
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveChatProvidersTest {
    private val sid = "0b6d2c1e-4f3a-4b2c-9d8e-1a2b3c4d5e6f"
    private val agents = AgentHarness.entries.filter { it != AgentHarness.SHELL }
    private val expected = mapOf(
        AgentHarness.CLAUDE to Triple("claude", "claude", AgentKind.CLAUDE),
        AgentHarness.CODEX to Triple("codex", "codex", AgentKind.CODEX),
        AgentHarness.PI to Triple("pi", "pi", AgentKind.PI),
        AgentHarness.HAX to Triple("hax", "hax", AgentKind.HAX),
        AgentHarness.CURSOR to Triple("cursor", "agent", AgentKind.CURSOR),
    )

    @Test
    fun everyAiChoiceIsOfferedAndCursorIsOneOfThem() {
        assertEquals(expected.keys, agents.toSet())
        assertEquals(AgentHarness.SHELL, AgentHarness.entries.last())
        assertEquals("Cursor", AgentHarness.CURSOR.label)
    }

    @Test
    fun everyAiChoiceStartsAsALiveChatOnTheHostLikeDesktop() {
        agents.forEach { harness ->
            val (provider, binary, _) = expected.getValue(harness)
            val name = "live-x1"
            val command = TmuxCommands.createLiveChat(name, "/srv/api", sid, harness)
            assertTrue(harness.name, command.contains("command -v $binary >/dev/null"))
            assertTrue(harness.name, command.contains("python3 - host status $provider $sid"))
            assertTrue(harness.name, command.contains("reader.py host enable $provider $sid --tmux $name --cwd /srv/api"))
            assertTrue(harness.name, command.contains("reader.py host start $provider $sid"))
            assertTrue(harness.name, command.contains("exec sleep infinity"))
            assertFalse(harness.name, command.contains("exec $binary"))
            assertEquals(harness.name, harness == AgentHarness.CLAUDE, command.contains("--permission-mode bypassPermissions"))
        }
    }

    @Test
    fun nonClaudeLaunchMatchesTheDesktopCommandExactly() {
        val command = TmuxCommands.createLiveChat("live-x1", "/srv/api", sid, AgentHarness.CODEX)
        assertTrue(command.contains(
            "reader.py host enable codex $sid --tmux live-x1 --cwd /srv/api >/dev/null 2>&1; " +
                "python3 ~/.multiprompt-runtime/reader.py host start codex $sid >/dev/null 2>&1; exec sleep infinity",
        ))
    }

    @Test
    fun aShellCannotStartALiveChat() {
        assertThrows(IllegalArgumentException::class.java) {
            TmuxCommands.createLiveChat("live-x1", "/srv/api", sid, AgentHarness.SHELL)
        }
    }

    @Test
    fun everyAiChoiceKeepsItsAssistantAfterTheChatListReloads() {
        agents.forEach { harness ->
            val agent = expected.getValue(harness).third
            val name = AgentKind.liveChatName(agent, "a-very-long-project-name", "mhz0abcd")
            TmuxCommands.createLiveChat(name, "/srv/api", sid, harness)
            assertEquals(harness.name, agent, AgentKind.detect(name, paneCommand = "sleep"))
            val session = createdSession("vps", CreatedSession(name, sid), "/srv/api", 1)
            assertEquals(harness.name, agent, session.agent)
            assertTrue(harness.name, session.liveChat)
        }
    }

    @Test
    fun claudeLiveChatNamesStayAsTheyWere() {
        assertEquals("live-api-x1", AgentKind.liveChatName(AgentKind.CLAUDE, "api", "x1"))
        assertEquals(AgentKind.CLAUDE, AgentKind.detect("live-api-x1", paneCommand = "sleep"))
        assertEquals(AgentKind.CLAUDE, AgentKind.detect("live-codex-x1", paneCommand = "sleep"))
        assertEquals(AgentKind.CLAUDE, AgentKind.detect("live-pi-tools-x1", paneCommand = "sleep"))
        assertEquals("live-cursor_api-x1", AgentKind.liveChatName(AgentKind.CURSOR, "api", "x1"))
    }

    @Test
    fun everyAiChoiceOpensChatBubblesAndSendsThroughItsOwnProvider() {
        agents.forEach { harness ->
            val (provider, _, agent) = expected.getValue(harness)
            val identity = ChatHistoryProtocol.Identity(agent, "live-x1", "/srv/api", sid)
            assertTrue(harness.name, ChatHistoryProtocol.supports(agent, sid))
            assertEquals(harness.name, provider, ChatHistoryProtocol.messageProvider(agent, sid))
            val request = JSONObject(ChatHistoryProtocol.request(1, identity, ChatHistory()))
            assertEquals(harness.name, provider, request.getString("provider"))
            assertEquals(harness.name, provider == "codex", request.getBoolean("codex"))
            assertEquals(provider, MessageProtocol.Scope(provider, sid, "live-x1").provider)
        }
    }

    @Test
    fun terminalSessionsKeepTheirOldReaderPath() {
        assertFalse(ChatHistoryProtocol.supports(AgentKind.PI, ""))
        assertFalse(ChatHistoryProtocol.supports(AgentKind.CURSOR, ""))
        assertNull(ChatHistoryProtocol.messageProvider(AgentKind.HAX, ""))
        val hax = JSONObject(ChatHistoryProtocol.request(1, ChatHistoryProtocol.Identity(AgentKind.HAX, "hax-x1", "/srv/api"), ChatHistory()))
        assertTrue(hax.getBoolean("codex"))
        assertFalse(hax.has("provider"))
    }

    @Test
    fun aHostedHaxChatAcceptsItsOwnHistoryHeader() {
        val identity = ChatHistoryProtocol.Identity(AgentKind.HAX, "live-hax_api-x1", "/srv/api", sid)
        val probe = "0".repeat(64)
        val header = "@mp\t1\thistory\tok\t$sid\t0\t0\t0\t0\thax\tlive-hax_api-x1\t0\t$probe\tdelta\t0"
        val wire = header + "\n" + """{"role":"status","no_transcript":true}""" + "\n" + TranscriptProtocol.END + "\n"
        val page = ChatHistoryProtocol.parse(JSONObject().put("id", 1).put("wire", wire).toString(), 1, identity)
        assertNotNull(page)
        assertEquals(sid, page!!.sid)
    }

    @Test
    fun aNewCursorChatWithNoTranscriptOpensEmpty() {
        val missing = JSONObject().put("id", 1).put("wire", "@mp\t1\thistory\tmissing\t-\t0\n" + TranscriptProtocol.END + "\n").toString()
        val cursor = ChatHistoryProtocol.parse(missing, 1, ChatHistoryProtocol.Identity(AgentKind.CURSOR, "live-cursor_api-x1", "/srv/api", sid))
        val loaded = ChatHistory().apply(cursor!!)
        assertEquals(sid, loaded.sid)
        assertTrue(loaded.messages.isEmpty())
        assertNull(ChatHistoryProtocol.parse(missing, 1, ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "live-api-x1", "/srv/api", sid)))
    }
}
