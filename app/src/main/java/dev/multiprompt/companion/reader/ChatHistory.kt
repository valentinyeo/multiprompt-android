package dev.multiprompt.companion.reader

import dev.multiprompt.companion.reader.ChatHistoryProtocol.Row
import dev.multiprompt.companion.model.AgentKind

data class ChatHistoryMessage(
    val key: String,
    val role: String,
    val text: String,
    val timestamp: String,
    val offset: Long,
    val end: Long,
    val requestId: String = "",
    val pending: Boolean = false,
    val delivery: String? = null,
) {
    fun transcript(): TranscriptMessage = TranscriptMessage(role, timestamp.ifEmpty { null }, text)
}

data class ChatHistory(
    val sid: String = "",
    val inode: Long = 0,
    val offset: Long = 0,
    val start: Long = 0,
    val mtime: Long = 0,
    val probe: String = "",
    val more: Boolean = false,
    val rows: List<Row> = emptyList(),
    val deliveries: Map<String, QueuedMessages.Delivery> = emptyMap(),
    val held: List<QueuedMessages.SentPrompt> = emptyList(),
) {
    val pendingQuestions: List<PendingQuestion> by lazy {
        val pending = LinkedHashMap<String, List<PendingQuestion>>()
        for (row in rows) {
            if (row.questionResult.isNotEmpty()) pending.remove(row.questionResult)
            if (row.questions.isNotEmpty()) pending[row.id] = row.questions
        }
        pending.values.flatten()
    }

    val messages: List<ChatHistoryMessage> by lazy {
        val grouped = LinkedHashMap<String, ChatHistoryMessage>()
        for (row in rows) {
            if (row.settlement != null || row.questions.isNotEmpty() || row.questionResult.isNotEmpty()) continue
            val key = "$sid:$inode:${row.role}:${row.id.ifEmpty { "@${row.offset}" }}"
            val previous = grouped[key]
            val text = when {
                previous == null -> row.text
                row.text == previous.text || previous.text.startsWith(row.text) -> previous.text
                row.text.startsWith(previous.text) -> row.text
                else -> previous.text + "\n" + row.text
            }
            grouped[key] = ChatHistoryMessage(key, row.role, text, previous?.timestamp ?: row.timestamp,
                previous?.offset ?: row.offset, row.end, row.id,
                row.queued && !QueuedMessages.accepted(row.text, QueuedMessages.timestampMillis(row.timestamp), rows, row.offset) &&
                    !QueuedMessages.answered(rows, row.offset))
        }
        grouped.values.toList()
    }

    fun displayMessages(screenOutput: String, agent: AgentKind,
        sent: List<QueuedMessages.SentPrompt>, nowMillis: Long): List<ChatHistoryMessage> {
        val echoes = QueuedMessages.pending(messages.map { it.transcript() }, screenOutput, agent, sent.filter { it.messageId.isEmpty() }, nowMillis).map { text ->
            val sentAt = sent.lastOrNull { QueuedMessages.normalize(it.text) == text }?.sentAtMillis
            ChatHistoryMessage("@queued:$text", "user", text, "", 0, 0,
                pending = !QueuedMessages.accepted(text, sentAt, rows))
        }
        val own = (sent.filter { it.messageId.isNotEmpty() } + held).distinctBy { it.messageId }.sortedBy { it.sentAtMillis }
        val delivered = mutableSetOf<String>()
        val current = messages.map { message ->
            val record = own.firstOrNull { prompt ->
                val state = deliveries[prompt.messageId] ?: prompt.delivery
                prompt.messageId !in delivered && state == QueuedMessages.Delivery.DELIVERED && prompt.sid == sid && prompt.inode == inode &&
                    message.role == "user" && message.offset >= prompt.afterOffset &&
                    QueuedMessages.normalize(message.text) == QueuedMessages.normalize(prompt.text)
            }
            if (record == null) message else {
                delivered.add(record.messageId)
                message.copy(pending = false, delivery = "Delivered")
            }
        }
        val managed = own.filter { it.messageId !in delivered }.map { prompt ->
            val state = if (prompt.delivery == QueuedMessages.Delivery.CANCELLED) prompt.delivery else deliveries[prompt.messageId] ?: prompt.delivery
            ChatHistoryMessage("@held:${prompt.messageId}", "user", prompt.text, "", 0, 0,
                requestId = prompt.messageId, pending = state !in setOf(QueuedMessages.Delivery.CANCELLED, QueuedMessages.Delivery.DELIVERED),
                delivery = when (state) {
                    QueuedMessages.Delivery.CANCELLED -> "Cancelled"
                    QueuedMessages.Delivery.DELIVERED -> "Delivered"
                    QueuedMessages.Delivery.QUEUED -> "Queued"
                    QueuedMessages.Delivery.SENDING, QueuedMessages.Delivery.SUBMITTED -> "Sending"
                })
        }
        return current + echoes + managed
    }

    fun apply(page: ChatHistoryProtocol.Page): ChatHistory {
        val sameSource = sid == page.sid && inode == page.inode
        when (page.mode) {
            "delta" -> require(sameSource && page.base == offset && page.oldest == start)
            "older" -> require(sameSource && page.end == start && page.oldest == page.base)
        }
        val retained = if (page.mode == "tail") emptyList() else rows
        val merged = (retained + page.rows).associateBy { Triple(it.offset, it.role, it.id) }.values.sortedBy { it.offset }
        return if (page.mode == "older") {
            copy(start = page.oldest, rows = merged, deliveries = deliveries + page.deliveries, held = page.held)
        } else {
            ChatHistory(page.sid, page.inode, page.end, page.oldest, page.mtime, page.probe, page.more, merged, (if (sameSource) deliveries else emptyMap()) + page.deliveries, page.held)
        }
    }
}
