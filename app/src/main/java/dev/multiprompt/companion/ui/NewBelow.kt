package dev.multiprompt.companion.ui

import dev.multiprompt.companion.reader.ChatHistoryMessage

internal fun newRepliesBetween(before: List<ChatHistoryMessage>, after: List<ChatHistoryMessage>): Int {
    if (before.isEmpty()) return 0
    if (after.indexOfFirst { it.key == before.first().key } > 0) return 0
    return (settledReplies(after) - settledReplies(before)).coerceAtLeast(0)
}

private fun settledReplies(messages: List<ChatHistoryMessage>): Int =
    messages.count { it.role == "assistant" && !it.pending }

internal fun jumpCountLabel(count: Int): String = if (count > 99) "99+" else count.toString()
