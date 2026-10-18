package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.ssh.TmuxText
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuedMessagesTest {
    private val now = Instant.parse("2026-05-01T12:00:00Z").toEpochMilli()
    private val recorded = "Post this this is reall good but use the one that is not pixelated " +
        "I didn't like the pixelated one in the end"
    private val captions = "Actually, do not post this. Add the captions first. Did you add captions? " +
        "We need captions. In this."
    private val cadence = "And did you do the right cadence with the the video that we got about how to do a " +
        "viral short where the cadence was sped up? I think this has the right cutting cadence. Right?"
    private val screen = """
        > Post this this is reall good but use the one that is not pixelated
          I didn't like the pixelated one in the end

        ● Bash(ffmpeg -i original.mp4 -vf scale=1080:1920 preview.mp4)
          ⎿  Process exited with code 0

        > Actually, do not post this. Add the captions first. Did you add captions?
          We need captions. In this.
        > And did you do the right cadence with the the video that we got about how to do a
          viral short where the cadence was sped up? I think this has the right cutting cadence. Right?

        ✻ Churning… (1m 38s · ↓ 453 tokens)
        ──────────────────────────────────────────────────────────────────────────────
        > Press up to edit queued messages
        ──────────────────────────────────────────────────────────────────────────────
          Opus 4.7 · bypass permissions on (shift+tab to cycle)
    """.trimIndent()

    @Test fun phoneSendIsQueuedUntilRecorded() {
        val sent = listOf(QueuedMessages.SentPrompt("Stop, don't do that", now))
        assertEquals(listOf("Stop, don't do that"), pending(sent = sent))
        assertTrue(pending(listOf(user("Stop, don't do that")), sent = sent).isEmpty())
    }

    @Test fun bugScreenYieldsExactlyTwoUnwrappedQueuedPrompts() {
        assertEquals(listOf(captions, cadence), pending(listOf(user(recorded)), screen))
        assertEquals(
            listOf(captions, cadence),
            pending(listOf(user(recorded)), TmuxText.leftAligned(screen)),
        )
    }

    @Test fun recordedScreenPromptsDisappearWithoutDuplicates() {
        val sent = listOf(QueuedMessages.SentPrompt(captions, now))
        assertEquals(listOf(cadence), pending(listOf(user(recorded), user(captions)), screen, sent))
        assertTrue(pending(listOf(user(recorded), user(captions), user(cadence)), screen, sent).isEmpty())
    }

    @Test fun screenNeedsQueueHint() {
        assertTrue(pending(listOf(user(recorded)), screen.replace("Press up to edit queued messages", "")).isEmpty())
        assertEquals(
            listOf(captions, cadence),
            pending(listOf(user(recorded)), screen.replace("Press up to edit queued messages", "PRESS UP TO EDIT QUEUED MESSAGES")),
        )
    }

    @Test fun phoneAndScreenCopiesAppearOnce() {
        assertEquals(
            listOf(captions, cadence),
            pending(listOf(user(recorded)), screen, listOf(QueuedMessages.SentPrompt(captions, now))),
        )
    }

    @Test fun expiredSendsAndSlashCommandsAreIgnored() {
        assertTrue(
            pending(sent = listOf(
                QueuedMessages.SentPrompt("Old message", now - 16 * 60_000),
                QueuedMessages.SentPrompt("  /model opus  ", now),
                QueuedMessages.SentPrompt(" \n\t ", now),
            )).isEmpty(),
        )
        assertEquals(listOf("Still pending"), pending(sent = listOf(QueuedMessages.SentPrompt("Still pending", now - 15 * 60_000))))
    }

    @Test fun phoneSendsAreOldestFirstWithoutDuplicates() {
        assertEquals(
            listOf("First prompt", "Second prompt"),
            pending(sent = listOf(
                QueuedMessages.SentPrompt("Second prompt", now),
                QueuedMessages.SentPrompt("  First\n\t prompt  ", now - 1000),
                QueuedMessages.SentPrompt("First prompt", now),
            )),
        )
    }

    @Test fun matchingNormalizesWhitespaceAndOnlyUsesUserMessages() {
        val sent = listOf(QueuedMessages.SentPrompt("Stop now", now))
        assertTrue(pending(listOf(user(" \nStop\t now \n")), sent = sent).isEmpty())
        assertEquals(listOf("Stop now"), pending(listOf(TranscriptMessage("assistant", null, "Stop now")), sent = sent))
    }

    @Test fun truncationMatchesInEitherDirectionOnlyWithFortyCharacters() {
        val full = "Please stop posting this video until the captions have been added"
        val prefix = full.take(40)
        assertTrue(pending(listOf(user(full)), sent = listOf(QueuedMessages.SentPrompt(prefix, now))).isEmpty())
        assertTrue(pending(listOf(user(prefix)), sent = listOf(QueuedMessages.SentPrompt(full, now))).isEmpty())
        assertEquals(listOf(full), pending(listOf(user(full.take(39))), sent = listOf(QueuedMessages.SentPrompt(full, now))))
        assertEquals(listOf("Stop now"), pending(listOf(user("Stop")), sent = listOf(QueuedMessages.SentPrompt("Stop now", now))))
    }

    @Test fun oldTranscriptDoesNotHideANewRepeatSend() {
        val sent = listOf(QueuedMessages.SentPrompt("Stop now", now))
        assertEquals(listOf("Stop now"), pending(listOf(user("Stop now", now - 5 * 60_000 - 1)), sent = sent))
        assertTrue(pending(listOf(user("Stop now", now - 5 * 60_000)), sent = sent).isEmpty())
        assertTrue(pending(listOf(user("Stop now", now)), sent = sent).isEmpty())
        assertTrue(pending(listOf(user("Stop now")), sent = sent).isEmpty())
        assertTrue(pending(listOf(TranscriptMessage("user", "invalid", "Stop now")), sent = sent).isEmpty())
    }

    @Test fun screenDeduplicatesTruncatedCopiesAndAcceptsClaudePromptMarker() {
        val truncated = captions.take(45)
        val repeated = screen.replace("✻ Churning", "❯ $truncated\n\n✻ Churning")
            .replace("> Actually", "❯ Actually")
        assertEquals(listOf(captions, cadence), pending(listOf(user(recorded)), repeated))
    }

    private fun user(text: String, timestampMillis: Long? = null) = TranscriptMessage(
        "user",
        timestampMillis?.let { Instant.ofEpochMilli(it).toString() },
        text,
    )

    private fun pending(
        transcript: List<TranscriptMessage> = emptyList(),
        output: String = "",
        sent: List<QueuedMessages.SentPrompt> = emptyList(),
    ): List<String> = QueuedMessages.pending(transcript, output, AgentKind.CLAUDE, sent, now)
}
