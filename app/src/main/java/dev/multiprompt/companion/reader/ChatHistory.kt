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
    val pendingQuestions: List<PendingQuestion> = emptyList(),
) {
    val messages: List<ChatHistoryMessage> by lazy {
        val grouped = LinkedHashMap<String, ChatHistoryMessage>()
        for (row in rows) {
            if ((row.settlement != null && row.text.isBlank()) || row.questions.isNotEmpty() || row.questionResult.isNotEmpty()) continue
            val key = "$sid:$inode:${row.role}:${row.id.ifEmpty { "@${row.offset}" }}"
            val previous = grouped[key]
            val text = when {
                previous == null -> row.text
                row.text == previous.text || previous.text.startsWith(row.text) -> previous.text
                row.text.startsWith(previous.text) -> row.text
                else -> previous.text + "\n" + row.text
            }
            grouped[key] = ChatHistoryMessage(key, row.role, text, previous?.timestamp ?: row.timestamp,
                previous?.offset ?: row.offset, row.end, row.id)
        }
        grouped.values.toList()
    }

    fun displayMessages(screenOutput: String, agent: AgentKind,
        sent: List<QueuedMessages.SentPrompt>, nowMillis: Long): List<ChatHistoryMessage> {
        val own = (held + sent.filter { it.messageId.isNotEmpty() }).distinctBy { it.messageId }.sortedBy { it.sentAtMillis }
        val heldIds = held.map { it.messageId }.toSet()
        fun state(prompt: QueuedMessages.SentPrompt): QueuedMessages.Delivery =
            if (prompt.messageId in heldIds) prompt.delivery else deliveries[prompt.messageId] ?: prompt.delivery
        val matched = mutableSetOf<String>()
        val native = sent.filter { it.messageId.isEmpty() }
        val matchedNative = mutableSetOf<String>()
        val current = messages.map { message ->
            val record = own.firstOrNull { prompt ->
                prompt.messageId !in matched && state(prompt) == QueuedMessages.Delivery.DELIVERED &&
                    prompt.sid.isNotEmpty() && prompt.sid == sid && prompt.inode == inode &&
                    message.role == "user" && message.offset >= prompt.afterOffset && message.text == prompt.text
            }
            if (record != null) {
                matched.add(record.messageId)
                message.copy(delivery = "Delivered")
            } else {
                val echo = native.firstOrNull { prompt ->
                    prompt.localId !in matchedNative && prompt.sid.isNotEmpty() && prompt.sid == sid &&
                        prompt.inode == inode && message.role == "user" && message.offset >= prompt.afterOffset &&
                        message.text == prompt.text
                }
                if (echo != null) matchedNative.add(echo.localId)
                message
            }
        }
        val echoes = native.filter { it.localId !in matchedNative }.map { prompt ->
            ChatHistoryMessage("@local:${prompt.localId}", "user", prompt.text, "", 0, 0)
        }
        val managed = own.filter { it.messageId !in matched }.map { prompt ->
            val delivery = state(prompt)
            ChatHistoryMessage("@held:${prompt.messageId}", "user", prompt.text, "", 0, 0,
                requestId = prompt.messageId, pending = delivery !in setOf(QueuedMessages.Delivery.CANCELLED, QueuedMessages.Delivery.DELIVERED),
                delivery = when (delivery) {
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
            copy(start = page.oldest, rows = merged, deliveries = deliveries + page.deliveries, held = page.held, pendingQuestions = page.pendingQuestions)
        } else {
            ChatHistory(page.sid, page.inode, page.end, page.oldest, page.mtime, page.probe, page.more, merged, (if (sameSource) deliveries else emptyMap()) + page.deliveries, page.held, page.pendingQuestions)
        }
    }
}
