package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.ssh.TmuxText
import java.time.Instant
import java.time.format.DateTimeParseException

object QueuedMessages {
    data class SentPrompt(val text: String, val sentAtMillis: Long)

    fun pending(
        transcript: List<TranscriptMessage>,
        screenOutput: String,
        agent: AgentKind,
        sent: List<SentPrompt>,
        nowMillis: Long,
    ): List<String> {
        val users = transcript.filter { it.role == "user" }
        val queued = mutableListOf<String>()
        sent.sortedBy { it.sentAtMillis }.forEach { prompt ->
            val text = normalize(prompt.text)
            if (text.isEmpty() || text.startsWith("/") || nowMillis - prompt.sentAtMillis > 15 * 60_000L) {
                return@forEach
            }
            val recorded = users.any { message ->
                matches(text, normalize(message.text)) &&
                    (timestampMillis(message.timestamp)?.let { it >= prompt.sentAtMillis - 5 * 60_000L } ?: true)
            }
            if (!recorded && queued.none { matches(it, text) }) queued.add(text)
        }
        if (screenOutput.contains("Press up to edit queued messages", ignoreCase = true)) {
            TmuxText.readerBlocks(TmuxText.withoutActiveComposer(screenOutput, agent), agent, emptyList())
                .filter { it.kind == TmuxText.ReaderBlockKind.USER_PROMPT }
                .forEach { block ->
                    val text = normalize(block.text)
                    if (text.isNotEmpty() && users.none { matches(text, normalize(it.text)) } &&
                        queued.none { matches(it, text) }
                    ) {
                        queued.add(text)
                    }
                }
        }
        return queued
    }

    private fun normalize(text: String): String = text.replace(WHITESPACE, " ").trim()

    private fun matches(first: String, second: String): Boolean = first == second ||
        (minOf(first.length, second.length) >= 40 && (first.startsWith(second) || second.startsWith(first)))

    private fun timestampMillis(timestamp: String?): Long? = try {
        timestamp?.let { Instant.parse(it).toEpochMilli() }
    } catch (_: DateTimeParseException) {
        null
    }

    private val WHITESPACE = Regex("\\s+")
}
