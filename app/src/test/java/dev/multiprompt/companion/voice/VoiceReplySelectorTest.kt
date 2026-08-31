package dev.multiprompt.companion.voice

import dev.multiprompt.companion.model.AgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceReplySelectorTest {
    @Test
    fun selectsCompletedProseAfterLatestPrompt() {
        val output = """
            ❯ First request
            Old answer.
            ❯ Second request
            • Read app.kt (completed)

            Final spoken answer.
            ❯
        """.trimIndent()

        assertEquals(
            "Final spoken answer.",
            VoiceReplySelector.completedReply(output, AgentKind.CODEX, waitingForInput = true)?.text,
        )
    }

    @Test
    fun ignoresReplyUntilSessionIsWaiting() {
        val output = """
            ❯ Do the work
            Partial answer.
            • Running tests (2s)
        """.trimIndent()

        assertNull(
            VoiceReplySelector.completedReply(output, AgentKind.CODEX, waitingForInput = false),
        )
    }

    @Test
    fun removesTerminalCompletionStatusFromSpeech() {
        val output = """
            › Make the change
            The change is live.
            Worked for 2m 14s
        """.trimIndent()

        assertEquals(
            "The change is live.",
            VoiceReplySelector.completedReply(output, AgentKind.CODEX, waitingForInput = true)?.text,
        )
    }

    @Test
    fun repeatedTextFromDifferentTurnsHasDifferentKey() {
        val first = VoiceReply("Done.", turnNumber = 1)
        val second = VoiceReply("Done.", turnNumber = 2)

        assertNotEquals(first.key, second.key)
    }

    @Test
    fun splitsLongSpeechOnNaturalBoundaries() {
        assertEquals(
            listOf("One sentence.", "Two words"),
            speechChunks("One sentence. Two words", maximumLength = 14),
        )
    }
}
