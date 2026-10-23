package dev.multiprompt.companion.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DissolvedSessionTest {

    private fun session(name: String, paneCommand: String = "") = TmuxSession(
        hostId = "host",
        name = name,
        windows = 1,
        attachedClients = 0,
        lastActivityEpochSeconds = 0,
        workingDirectory = "/projects/$name",
        paneCommand = paneCommand,
    )

    @Test
    fun anArchivedClaudeSessionResumesItsExactConversationWhenTheIdIsKnown() {
        val dissolved = DissolvedSession.from(
            session = session("claude-work"),
            workspaceId = null,
            workspaceName = "",
            claudeSessionId = "462717f8-6e8b-46bf-ab93-de7095ed3618",
        )

        assertEquals("claude --resume 462717f8-6e8b-46bf-ab93-de7095ed3618", dissolved.resumeCommand)
    }

    @Test
    fun onlyBoundedIdsResumeAnExactConversation() {
        for (id in listOf("abc;touch x", "\$(id)", "", "a".repeat(65), "abc_def", "abc\n")) {
            assertEquals("claude --continue", DissolvedSession.from(session("claude-work"), null, "", id).resumeCommand)
        }
        for (id in listOf("462717f8-6e8b-46bf-ab93-de7095ed3618", "a", "A0-".repeat(21) + "z")) {
            assertEquals("claude --resume $id", DissolvedSession.from(session("claude-work"), null, "", id).resumeCommand)
        }
    }

    @Test
    fun everyAgentThatCanContinueGetsAResumeCommand() {
        assertEquals(
            "claude --continue",
            DissolvedSession.from(session("claude-work"), null, "").resumeCommand,
        )
        assertEquals(
            "codex resume --last",
            DissolvedSession.from(session("codex-work"), null, "").resumeCommand,
        )
        assertEquals(
            "pi --continue",
            DissolvedSession.from(session("pi-work"), null, "").resumeCommand,
        )
        assertEquals(
            "hax --continue",
            DissolvedSession.from(session("hax-work"), null, "").resumeCommand,
        )
        assertEquals(
            "cursor-agent --continue",
            DissolvedSession.from(session("cursor-work", paneCommand = "cursor-agent"), null, "")
                .resumeCommand,
        )
        assertEquals(
            "",
            DissolvedSession.from(session("plain-shell"), null, "").resumeCommand,
        )
    }
}
