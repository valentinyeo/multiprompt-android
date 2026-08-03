package dev.multiprompt.companion.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxCommandsTest {
    @Test
    fun captureQuotesTheCompleteTarget() {
        val command = TmuxCommands.capture("a'; reboot")

        assertEquals("tmux capture-pane -p -J -S -200 -t 'a'\"'\"'; reboot:'", command)
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
    fun streamUsesOneFixedFramedCaptureLoop() {
        val command = TmuxCommands.stream("work")

        assertTrue(command.contains("while tmux has-session -t 'work:'"))
        assertTrue(command.contains("tmux capture-pane -p -J -S -200 -t 'work:'"))
        assertTrue(command.contains(TmuxCommands.SNAPSHOT_PREFIX))
        assertTrue(command.contains("sleep 1"))
    }
}
