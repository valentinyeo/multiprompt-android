package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.ssh.TmuxText
import java.time.Instant
import java.time.format.DateTimeParseException

object QueuedMessages {
    enum class Delivery { SENDING, QUEUED, SUBMITTED, DELIVERED, CANCELLED }

    data class SentPrompt(
        val text: String,
        val sentAtMillis: Long,
        val messageId: String = "",
        val delivery: Delivery = Delivery.SENDING,
        val sid: String = "",
        val inode: Long = 0,
        val afterOffset: Long = 0,
    )

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
            val recorded = users.any { message -> recorded(prompt, message) }
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

    internal fun recorded(prompt: SentPrompt, message: TranscriptMessage): Boolean =
        message.role == "user" && matches(normalize(prompt.text), normalize(message.text)) &&
            (timestampMillis(message.timestamp)?.let { it >= prompt.sentAtMillis - 5 * 60_000L } ?: true)

    internal fun accepted(text: String, sentAtMillis: Long?, rows: List<ChatHistoryProtocol.Row>, afterOffset: Long = 0): Boolean {
        val latest = rows.lastOrNull { row ->
            val fact = row.settlement ?: return@lastOrNull false
            val fresh = if (sentAtMillis != null) timestampMillis(row.timestamp)?.let { it >= sentAtMillis } == true
                else row.offset >= afterOffset
            fresh && fact.operation != ChatHistoryProtocol.QueueOperation.REJECTED &&
                (normalize(text) == normalize(fact.content) || fact.content.isEmpty() && fact.operation in setOf(
                    ChatHistoryProtocol.QueueOperation.DEQUEUE, ChatHistoryProtocol.QueueOperation.POP_ALL,
                ))
        }?.settlement ?: return false
        return latest.operation != ChatHistoryProtocol.QueueOperation.ENQUEUE
    }

    internal fun answered(rows: List<ChatHistoryProtocol.Row>, afterOffset: Long): Boolean = rows.any { row ->
        row.role == "assistant" && row.settlement == null && row.text.isNotBlank() && row.offset > afterOffset
    }

    internal fun normalize(text: String): String = text.replace(WHITESPACE, " ").trim()

    private fun matches(first: String, second: String): Boolean = first == second ||
        (minOf(first.length, second.length) >= 40 && (first.startsWith(second) || second.startsWith(first)))

    internal fun timestampMillis(timestamp: String?): Long? = try {
        timestamp?.let { Instant.parse(it).toEpochMilli() }
    } catch (_: DateTimeParseException) {
        null
    }

    private val WHITESPACE = Regex("\\s+")
}
