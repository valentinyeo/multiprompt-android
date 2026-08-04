package dev.multiprompt.companion.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxTextTest {
    @Test
    fun removesDesktopMarginsFromEveryCapturedLine() {
        val captured = "  first line\n      wrapped line\n\n    final line"

        assertEquals(
            "first line\nwrapped line\n\nfinal line",
            TmuxText.leftAligned(captured),
        )
    }

    @Test
    fun decodesSnapshotHex() {
        assertEquals("hello\nworld", TmuxText.decodeHex("68656c6c6f0a776f726c64"))
    }

    @Test
    fun removesClaudeComposerAndFooterFromMobileTranscript() {
        val captured = """
            Finished the implementation.
            ______________________________
            __________
            ❯ test
            ______________________________
            Opus 5.4 medium | project
            bypass permissions on
        """.trimIndent()

        assertEquals(
            "Finished the implementation.",
            TmuxText.withoutActiveComposer(captured),
        )
    }

    @Test
    fun removesCodexComposerAndFooterFromMobileTranscript() {
        val captured = """
            Completed the requested changes.

            › Improve documentation in @filename

            gpt-5.6-sol high fast · project
        """.trimIndent()

        assertEquals(
            "Completed the requested changes.",
            TmuxText.withoutActiveComposer(captured),
        )
    }

    @Test
    fun leavesOrdinaryShellOutputUntouched() {
        val captured = "build complete\nuser@host:${'$'}"

        assertEquals(captured, TmuxText.withoutActiveComposer(captured))
    }

    @Test
    fun detectsClaudeAndCodexIdleComposers() {
        assertTrue(TmuxText.isWaitingForInput("Finished the task.\n❯"))
        assertTrue(TmuxText.isWaitingForInput("Completed the change.\n› Ask Codex"))
        assertTrue(TmuxText.isWaitingForInput("Press up to edit queued messages"))
    }

    @Test
    fun activeOutputDoesNotNeedInput() {
        assertFalse(TmuxText.isWaitingForInput("Building the APK…\nRunning tests"))
    }
}
