package dev.multiprompt.companion.reader

import dev.multiprompt.companion.reader.ChatHistoryProtocol.Row

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
    val sharedRow: MessageProtocol.Row? = null,
    val unknownId: String? = null,
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

    fun managedMessages(replica: MessageReplica?, unresolved: List<MessageJournal.Entry>): List<ChatHistoryMessage> {
        val source = rows.toMutableList()
        replica?.nativeFacts?.values?.forEach { fact ->
            if (fact.inode == inode && fact.offset != null && fact.offset >= offset) {
                val parsed = runCatching { ChatHistoryProtocol.parseRow(org.json.JSONObject(fact.json).getJSONObject("row")) }.getOrNull()
                if (parsed != null) source.add(parsed)
            }
        }
        val grouped = LinkedHashMap<String, ChatHistoryMessage>()
        source.distinctBy { listOf(it.offset, it.end, it.role, it.id, it.normalized) }.sortedBy { it.offset }
            .filter { (it.settlement == null || it.text.isNotBlank()) && it.questions.isEmpty() && it.questionResult.isEmpty() }
            .forEach { row ->
                val metadata = if (row.normalized.isEmpty()) org.json.JSONObject() else org.json.JSONObject(row.normalized)
                val readId = metadata.optString("read_id")
                val segment = if (metadata.has("segment")) metadata.get("segment").toString() else ""
                val identity = when {
                    readId.isNotEmpty() -> "read:$readId:$segment"
                    row.id.isNotEmpty() -> "native:${row.id}:$segment"
                    else -> "@${row.offset}:${row.end}"
                }
                val key = "$sid:$inode:${row.role}:$identity"
                val previous = grouped[key]
                val text = when {
                    previous == null -> row.text
                    previous.text.startsWith(row.text) -> previous.text
                    row.text.startsWith(previous.text) -> row.text
                    else -> previous.text + "\n" + row.text
                }
                grouped[key] = ChatHistoryMessage(key, row.role, text, previous?.timestamp ?: row.timestamp,
                    previous?.offset ?: row.offset, row.end, row.id)
            }
        val occurrences = grouped.values.toList()
        val cards = replica?.messages?.values.orEmpty().filter { it.state != MessageProtocol.State.CANCELLED }.sortedBy { it.position }.map { row ->
            ChatHistoryMessage("@shared:${row.id}", "user", row.text, "", 0, 0, row.id,
                pending = row.state in setOf(MessageProtocol.State.QUEUED, MessageProtocol.State.SENT),
                delivery = row.state.name.lowercase().replaceFirstChar { it.uppercase() }, sharedRow = row)
        }
        val unknown = unresolved.filter { it.operation == "submit" && replica?.messages?.containsKey(it.messageId) != true }.map { entry ->
            ChatHistoryMessage("@unknown:${entry.id}", "user", entry.text.orEmpty(), "", 0, 0, entry.messageId,
                delivery = "Confirmation unknown", unknownId = entry.id)
        }
        return occurrences + cards + unknown
    }

    internal fun deliveryState(prompt: QueuedMessages.SentPrompt): QueuedMessages.Delivery {
        val terminal = setOf(QueuedMessages.Delivery.DELIVERED, QueuedMessages.Delivery.CANCELLED)
        val sameSource = prompt.sid.isEmpty() || prompt.sid == sid
        val mapped = deliveries[prompt.messageId].takeIf { sameSource }
        val heldRecord = held.firstOrNull { it.messageId == prompt.messageId && sameSource }
        return when {
            mapped in terminal -> mapped!!
            prompt.delivery in terminal -> prompt.delivery
            heldRecord != null -> heldRecord.delivery
            mapped != null -> mapped
            else -> prompt.delivery
        }
    }

    fun displayMessages(sent: List<QueuedMessages.SentPrompt>): List<ChatHistoryMessage> {
        val local = sent.filter { it.sid.isEmpty() || sid.isEmpty() || it.sid == sid }
        val own = (local.filter { it.messageId.isNotEmpty() && it.delivery in setOf(QueuedMessages.Delivery.DELIVERED, QueuedMessages.Delivery.CANCELLED) } +
            held + local.filter { it.messageId.isNotEmpty() }).distinctBy { it.messageId }.sortedBy { it.sentAtMillis }
        fun state(prompt: QueuedMessages.SentPrompt) = deliveryState(prompt)
        val matched = mutableSetOf<String>()
        val native = local.filter { it.messageId.isEmpty() }
        val current = messages.map { message ->
            val record = own.firstOrNull { prompt ->
                prompt.messageId !in matched && state(prompt) == QueuedMessages.Delivery.DELIVERED &&
                    prompt.sid.isNotEmpty() && prompt.sid == sid && prompt.inode == inode &&
                    message.role == "user" && message.offset >= prompt.afterOffset && message.text.trimEnd(' ') == prompt.text.trimEnd(' ')
            }
            if (record != null) {
                matched.add(record.messageId)
                message.copy(delivery = "Delivered")
            } else message
        }
        val echoes = native.map { prompt ->
            ChatHistoryMessage("@local:${prompt.localId}", "user", prompt.text, "", 0, 0,
                delivery = "Local · unconfirmed")
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

    internal fun resetSeed(): ChatHistory = ChatHistory(
        sid = sid,
        deliveries = deliveries.filterValues { it == QueuedMessages.Delivery.DELIVERED || it == QueuedMessages.Delivery.CANCELLED },
    )

    fun apply(page: ChatHistoryProtocol.Page): ChatHistory {
        val sameSource = sid == page.sid && inode == page.inode
        when (page.mode) {
            "delta" -> require(sameSource && page.base == offset && page.oldest == start)
            "older" -> require(sameSource && page.end == start && page.oldest == page.base)
        }
        val retained = if (page.mode == "tail") emptyList() else rows
        val merged = (retained + page.rows).associateBy { listOf(it.offset, it.end, it.role, it.id, it.normalized) }.values.sortedBy { it.offset }
        val previousDeliveries = when {
            sameSource -> deliveries
            sid == page.sid -> resetSeed().deliveries
            else -> emptyMap()
        }
        val terminal = setOf(QueuedMessages.Delivery.DELIVERED, QueuedMessages.Delivery.CANCELLED)
        val mergedDeliveries = previousDeliveries + page.deliveries + previousDeliveries.filterValues { it in terminal }
        return if (page.mode == "older") {
            copy(start = page.oldest, rows = merged, deliveries = mergedDeliveries, held = page.held, pendingQuestions = page.pendingQuestions)
        } else {
            ChatHistory(page.sid, page.inode, page.end, page.oldest, page.mtime, page.probe, page.more, merged, mergedDeliveries, page.held, page.pendingQuestions)
        }
    }
}
