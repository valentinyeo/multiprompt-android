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
    val unknownId: String? = null,
    val cancellable: Boolean = false,
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
    val clearedAt: Double = 0.0,
) {
    val messages: List<ChatHistoryMessage> by lazy { transcript(rows) }

    private fun transcript(source: List<Row>): List<ChatHistoryMessage> {
        val grouped = LinkedHashMap<String, ChatHistoryMessage>()
        source.distinctBy { listOf(it.offset, it.end, it.role, it.id, it.normalized) }.sortedBy { it.offset }
            .filter { (it.settlement == null || it.text.isNotBlank()) && it.questions.isEmpty() && it.questionResult.isEmpty() }
            .forEach { row ->
                val metadata = if (row.normalized.isEmpty()) org.json.JSONObject() else org.json.JSONObject(row.normalized)
                val readId = metadata.optString("read_id")
                val segment = if (metadata.has("segment")) metadata.get("segment").toString() else ""
                val identity = when {
                    row.role == "user" && row.id.isNotEmpty() -> row.id
                    readId.isNotEmpty() -> "read:$readId:$segment"
                    row.id.isNotEmpty() -> row.id
                    else -> "@${row.offset}"
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
        return grouped.values.toList()
    }

    fun projectMessages(
        replica: MessageReplica? = null,
        unresolved: List<MessageJournal.Entry> = emptyList(),
        now: Double? = null,
        offline: Boolean = false,
        seen: Map<String, Double> = emptyMap(),
        sent: List<QueuedMessages.SentPrompt>? = null,
        waiting: List<PendingPrompts.Entry> = emptyList(),
    ): List<ChatHistoryMessage> {
        val scoped = replica?.messages?.values.orEmpty().filter { it.sid == sid }
        val waitingIds = waiting.map { it.id }.toSet()
        val source = rows.toMutableList()
        replica?.nativeFacts?.values?.filter { it.sid == sid }?.forEach { fact ->
            if (fact.inode == inode && fact.offset != null && fact.offset >= offset) {
                val parsed = runCatching { ChatHistoryProtocol.parseRow(org.json.JSONObject(fact.json).getJSONObject("row")) }.getOrNull()
                if (parsed != null) source.add(parsed)
            }
        }
        val transcript = transcript(source)
        val latest = transcript.filter { it.role == "user" }.mapNotNull { LegacyMessageIdentity.seconds(it.timestamp) }.maxOrNull()
        val host = replica?.host ?: HostStream()
        val cards = if (sent == null) scoped.filter { it.state != MessageProtocol.State.CANCELLED && !clearedMessage(it) }.sortedBy { it.position }.map { row ->
            val notSent = notSent(row, now, seen[row.id])
            Card(ChatHistoryMessage("@shared:${row.id}", "user", row.text, "", 0, 0, row.id,
                pending = !notSent && row.state in setOf(MessageProtocol.State.QUEUED, MessageProtocol.State.SENT),
                delivery = if (notSent) NOT_SENT else null,
                cancellable = cancellable(row, now, seen[row.id])),
                host.sentRows[row.id].orEmpty().toSet() + row.id,
                visible = row.state in setOf(MessageProtocol.State.QUEUED, MessageProtocol.State.SENT, MessageProtocol.State.FAILED) ||
                    row.id == host.messageId || latest == null || latest <= row.created + CLOCK_SLACK,
                shared = row)
        } else {
            val local = sent.filter { it.sid.isEmpty() || sid.isEmpty() || it.sid == sid }
            val own = (local.filter { it.messageId.isNotEmpty() && it.delivery in setOf(QueuedMessages.Delivery.DELIVERED, QueuedMessages.Delivery.CANCELLED) } +
                held.filter { it.sid.isEmpty() || it.sid == sid } + local.filter { it.messageId.isNotEmpty() })
                .distinctBy { it.messageId }.sortedBy { it.sentAtMillis }
            local.filter { it.messageId.isEmpty() }.distinctBy { it.localId }.map { prompt ->
                Card(ChatHistoryMessage("@local:${prompt.localId}", "user", prompt.text, "", 0, 0, delivery = "Local · unconfirmed"))
            } + own.map { prompt ->
                val delivery = deliveryState(prompt)
                Card(ChatHistoryMessage("@held:${prompt.messageId}", "user", prompt.text, "", 0, 0, prompt.messageId,
                    pending = delivery !in setOf(QueuedMessages.Delivery.CANCELLED, QueuedMessages.Delivery.DELIVERED),
                    delivery = when (delivery) {
                        QueuedMessages.Delivery.CANCELLED -> "Cancelled"
                        QueuedMessages.Delivery.DELIVERED -> "Delivered"
                        QueuedMessages.Delivery.QUEUED -> "Queued"
                        QueuedMessages.Delivery.SENDING, QueuedMessages.Delivery.SUBMITTED -> "Sending"
                    }), setOf(prompt.messageId), legacy = prompt.takeIf { delivery == QueuedMessages.Delivery.DELIVERED })
            }
        }
        val claimed = HashMap<Int, Card>()
        val placed = mutableSetOf<String>()
        for (card in cards) {
            transcript.forEachIndexed { index, message ->
                if (message.role == "user" && message.requestId.isNotEmpty() && message.requestId in card.aliases) {
                    claimed[index] = card
                    placed.add(card.message.key)
                }
            }
        }
        var floor = -1
        for (card in cards.filter { it.message.key !in placed }) {
            val index = transcript.indices.firstOrNull { index ->
                index !in claimed && transcript[index].requestId !in waitingIds && when {
                    card.shared != null && host.sentRows[card.shared.id].isNullOrEmpty() ->
                        index > floor && LegacyMessageIdentity.shared(transcript[index], card.shared)
                    card.legacy != null -> LegacyMessageIdentity.delivered(transcript[index], card.legacy, sid, inode)
                    else -> false
                }
            }
            if (index != null) {
                claimed[index] = card
                placed.add(card.message.key)
                floor = maxOf(floor, index)
            }
        }
        val aliases = HashMap<String, String>()
        host.sentRows.forEach { (id, uuids) -> (uuids + id).forEach { aliases[it] = id } }
        scoped.forEach { aliases[it.id] = it.id }
        waitingIds.forEach { aliases.putIfAbsent(it, it) }
        val projected = LinkedHashMap<String, ChatHistoryMessage>()
        val replyOwners = HashMap<Int, ChatHistoryMessage>()
        var replyOwner: ChatHistoryMessage? = null
        transcript.forEachIndexed { index, message ->
            val card = claimed[index]
            val phoneId = aliases[message.requestId].takeIf { message.role == "user" }
            val occurrence = when {
                card != null -> message.copy(key = card.message.key, requestId = card.message.requestId, delivery = card.message.delivery)
                phoneId != null -> message.copy(key = "@shared:$phoneId", requestId = phoneId)
                else -> message
            }
            projected[occurrence.key] = occurrence
            if (LegacyMessageIdentity.replyBoundary(occurrence)) replyOwner = occurrence
            else replyOwner?.let { replyOwners[index] = it }
        }
        val blocks = (host.earlier + host.spoken.map { it.copy(turn = host.turn) }).distinctBy { it.turn to it.item }
        val anchors = cards.filter { it.message.requestId.isNotEmpty() }.associate { it.message.requestId to it.message.key }.toMutableMap()
        projected.values.filter { it.role == "user" && it.requestId.isNotEmpty() }.forEach { anchors[it.requestId] = it.key }
        val replies = LinkedHashMap<String, MutableList<HostBlock>>()
        val unmatched = mutableListOf<HostBlock>()
        val matchedReplies = HashSet<Int>()
        for (block in blocks) {
            val index = transcript.indices.firstOrNull { index ->
                val message = transcript[index]
                index !in matchedReplies && message.role == "assistant" &&
                    (message.requestId.isNotEmpty() && rowKey(message.requestId) == rowKey(block.item) ||
                        LegacyMessageIdentity.reply(message, block, replyOwners[index]))
            }
            if (index != null) {
                matchedReplies.add(index)
                block.messageId?.let { anchors[it] = transcript[index].key }
            } else {
                val anchor = block.messageId?.let { anchors[it] }
                if (anchor == null) unmatched.add(block) else replies.getOrPut(anchor) { mutableListOf() }.add(block)
            }
        }
        fun appendReply(block: HostBlock) {
            val key = "@live:${block.turn}:${block.item}"
            projected[key] = ChatHistoryMessage(key, "assistant", block.text, "", 0, 0, pending = !block.done)
        }
        val ordered = projected.values.toList()
        projected.clear()
        fun append(message: ChatHistoryMessage) {
            projected[message.key] = message
            replies.remove(message.key).orEmpty().forEach(::appendReply)
        }
        ordered.forEach(::append)
        unmatched.filter { it.turn != host.turn }.forEach(::appendReply)
        cards.filter { it.message.key !in placed && it.visible }.forEach { append(it.message) }
        replies.values.flatten().forEach(::appendReply)
        val canonical = scoped.associateBy { it.id }
        unresolved.filter { it.operation == "submit" && it.scope.sid == sid && canonical[it.messageId]?.text != it.text }.forEach { entry ->
            val key = if (entry.messageId in canonical) "@unknown:${entry.id}" else "@shared:${entry.messageId}"
            if (projected.values.none { it.role == "user" && it.requestId == entry.messageId && it.text == entry.text }) {
                projected[key] = ChatHistoryMessage(key, "user", entry.text.orEmpty(), "", 0, 0, entry.messageId,
                    delivery = if (offline) NOT_SENT else null, unknownId = entry.id)
            }
        }
        waiting.distinctBy { it.id }.filter { entry ->
            entry.id !in canonical && projected.values.none { it.role == "user" && it.requestId == entry.id }
        }.forEach { entry ->
            projected["@shared:${entry.id}"] = ChatHistoryMessage("@shared:${entry.id}", "user", entry.text, "", 0, 0, entry.id,
                pending = entry.error == null, delivery = if (entry.error == null) PendingPrompts.WAITING_LABEL else NOT_SENT)
        }
        unmatched.filter { it.turn == host.turn }.forEach(::appendReply)
        return projected.values.toList()
    }

    private data class Card(
        val message: ChatHistoryMessage,
        val aliases: Set<String> = emptySet(),
        val visible: Boolean = true,
        val shared: MessageProtocol.Row? = null,
        val legacy: QueuedMessages.SentPrompt? = null,
    )

    private fun rowKey(id: String): String = id.substringBefore(":text:")

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

    internal fun resetSeed(): ChatHistory = ChatHistory(
        sid = sid,
        deliveries = deliveries.filterValues { it == QueuedMessages.Delivery.DELIVERED || it == QueuedMessages.Delivery.CANCELLED },
        clearedAt = clearedAt,
    )

    internal fun clearedMessage(row: MessageProtocol.Row): Boolean = clearedAt > 0 && row.created < clearedAt - CLOCK_SLACK

    fun apply(page: ChatHistoryProtocol.Page): ChatHistory {
        val fresh = page.inode == 0L && page.end == 0L && page.rows.isEmpty()
        val mode = if (fresh) "tail" else page.mode
        val sameSource = sid == page.sid && inode == page.inode
        when (mode) {
            "delta" -> require(sameSource && page.base == offset && page.oldest == start)
            "older" -> require(sameSource && page.end == start && page.oldest == page.base)
        }
        val replaced = mode == "tail" && page.sid == sid && inode != 0L && page.inode != 0L && page.inode != inode
        val cleared = when {
            page.sid != sid -> 0.0
            replaced -> maxOf(clearedAt, seconds(mtime), seconds(page.mtime))
            else -> clearedAt
        }
        val retained = if (mode == "tail") emptyList() else rows
        val merged = (retained + page.rows).associateBy { listOf(it.offset, it.end, it.role, it.id, it.normalized) }.values.sortedBy { it.offset }
        val previousDeliveries = when {
            sameSource -> deliveries
            sid == page.sid -> resetSeed().deliveries
            else -> emptyMap()
        }
        val terminal = setOf(QueuedMessages.Delivery.DELIVERED, QueuedMessages.Delivery.CANCELLED)
        val mergedDeliveries = previousDeliveries + page.deliveries + previousDeliveries.filterValues { it in terminal }
        return if (mode == "older") {
            copy(start = page.oldest, rows = merged, deliveries = mergedDeliveries, held = page.held, pendingQuestions = page.pendingQuestions, clearedAt = cleared)
        } else {
            ChatHistory(page.sid, page.inode, page.end, page.oldest, page.mtime, page.probe, page.more, merged, mergedDeliveries, page.held, page.pendingQuestions, cleared)
        }
    }

    companion object {
        internal const val CLOCK_SLACK = 2.0
        const val NOT_SENT = "Not sent"
        private const val NOT_SENT_WAIT = 600.0

        fun notSent(row: MessageProtocol.Row, now: Double?, seen: Double? = null): Boolean = when (row.state) {
            MessageProtocol.State.FAILED -> true
            MessageProtocol.State.QUEUED, MessageProtocol.State.SENT -> now != null && now - maxOf(row.created, seen ?: row.created) >= NOT_SENT_WAIT
            else -> false
        }

        fun cancellable(row: MessageProtocol.Row, now: Double?, seen: Double? = null): Boolean =
            row.state in setOf(MessageProtocol.State.QUEUED, MessageProtocol.State.SENT) && !notSent(row, now, seen)

        private fun seconds(nanos: Long): Double = if (nanos > 0) nanos / 1_000_000_000.0 else 0.0
    }
}
