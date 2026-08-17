package dev.multiprompt.companion.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxCommandsTest {
    @Test
    fun captureQuotesTheCompleteTarget() {
        val command = TmuxCommands.capture("a'; reboot")

        assertEquals("tmux capture-pane -p -J -S -2000 -t 'a'\"'\"'; reboot:'", command)
    }

    @Test
    fun promptCommandReadsContentFromStdin() {
        val command = TmuxCommands.pastePrompt("codex-main")

        assertTrue(command.contains("tmux load-buffer"))
        assertTrue(command.contains("tmux paste-buffer -dpr"))
        assertTrue(command.contains("-t 'codex-main:'"))
        assertFalse(command.contains("prompt text"))
        assertFalse(command.contains(" -S"))
    }

    @Test
    fun focusedActionsContainOnlyAllowlistedKeys() {
        assertEquals(
            "tmux send-keys -t 'work:' Enter",
            TmuxCommands.action("work", TmuxAction.ENTER),
        )
        assertEquals(
            "tmux send-keys -t 'work:' C-c",
            TmuxCommands.action("work", TmuxAction.INTERRUPT),
        )
    }

    @Test
    fun modelPickerSelectionUsesOnlyAValidatedNumberKey() {
        assertEquals(
            "tmux send-keys -t 'work:' 3",
            TmuxCommands.modelPickerOption("work", 3),
        )
        assertFalse(runCatching { TmuxCommands.modelPickerOption("work", 0) }.isSuccess)
        assertFalse(runCatching { TmuxCommands.modelPickerOption("work", 10) }.isSuccess)
    }

    @Test
    fun streamUsesOneFixedFramedCaptureLoop() {
        val command = TmuxCommands.stream("work")

        assertTrue(command.contains("while tmux has-session -t 'work:'"))
        assertTrue(command.contains("tmux capture-pane -p -J -S -2000 -t 'work:'"))
        assertTrue(command.contains("tail -c 524288"))
        assertTrue(command.contains("mp_current=\$(cksum"))
        assertTrue(command.contains("mp_previous=\$mp_current"))
        assertTrue(command.contains(TmuxCommands.SNAPSHOT_PREFIX))
        assertTrue(command.contains("sleep 1"))
    }

    @Test
    fun claudeSessionCreationQuotesWorkspaceAndSession() {
        val command = TmuxCommands.createClaudeSession("claude-work", "/tmp/a'; touch /bad")

        assertTrue(command.contains("-s 'claude-work'"))
        assertTrue(command.contains("-c '/tmp/a'\"'\"'; touch /bad'"))
        assertTrue(command.contains("exec claude --dangerously-skip-permissions"))
        assertTrue(command.contains(TmuxCommands.CREATED_PREFIX))
    }

    @Test
    fun fullShellSessionStartsWithoutAnAgentCommand() {
        val command = TmuxCommands.createShellSession("shell-work", "/srv/work")

        assertTrue(command.contains("tmux new-session -d -s 'shell-work' -c '/srv/work'"))
        assertFalse(command.contains("claude"))
    }

    @Test
    fun dissolveTargetsExactlyOneQuotedSession() {
        assertEquals(
            "tmux kill-session -t '=work'",
            TmuxCommands.dissolveSession("work"),
        )
        assertEquals(
            "tmux kill-session -t '=a'\"'\"'; reboot'",
            TmuxCommands.dissolveSession("a'; reboot"),
        )
    }

    @Test
    fun renameChangesTheVisibleWindowButKeepsTheStableSessionId() {
        assertEquals(
            "tmux rename-window -t 'work:' 'New name'",
            TmuxCommands.renameWindow("work", "New name"),
        )
        assertEquals(
            "tmux rename-window -t 'a'\"'\"'; reboot:' 'It'\"'\"'s safe'",
            TmuxCommands.renameWindow("a'; reboot", "It's safe"),
        )
    }
}
