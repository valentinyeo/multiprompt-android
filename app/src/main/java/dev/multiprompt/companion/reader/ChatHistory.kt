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
) {
    val messages: List<ChatHistoryMessage> by lazy {
        val grouped = LinkedHashMap<String, ChatHistoryMessage>()
        for (row in rows) {
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

    fun apply(page: ChatHistoryProtocol.Page): ChatHistory {
        val sameSource = sid == page.sid && inode == page.inode
        when (page.mode) {
            "delta" -> require(sameSource && page.base == offset && page.oldest == start)
            "older" -> require(sameSource && page.end == start && page.oldest == page.base)
        }
        val retained = if (page.mode == "tail") emptyList() else rows
        val merged = (retained + page.rows).associateBy { Pair(it.offset, it.role) }.values.sortedBy { it.offset }
        return if (page.mode == "older") {
            copy(start = page.oldest, rows = merged)
        } else {
            ChatHistory(page.sid, page.inode, page.end, page.oldest, page.mtime, page.probe, page.more, merged)
        }
    }
}
