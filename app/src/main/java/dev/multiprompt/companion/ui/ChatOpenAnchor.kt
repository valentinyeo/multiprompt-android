package dev.multiprompt.companion.ui

import dev.multiprompt.companion.reader.ChatHistoryMessage
import java.time.Instant

internal object ChatOpenAnchor {
    fun unreadKey(messages: List<ChatHistoryMessage>, markedKey: String?, readAtEpochSeconds: Long?): String? {
        if (markedKey != null) return markedKey
        if (readAtEpochSeconds == null || readAtEpochSeconds <= 0L) return null
        var first: String? = null
        for (index in messages.indices.reversed()) {
            val message = messages[index]
            if (message.role != "assistant" || message.pending) continue
            val sentAt = epochSeconds(message.timestamp) ?: continue
            if (sentAt <= readAtEpochSeconds) break
            first = message.key
        }
        return first
    }

    fun leading(questionCount: Int, showDots: Boolean, showModelChoices: Boolean): Int =
        questionCount + (if (showDots) 1 else 0) + (if (showModelChoices) 1 else 0)

    fun listIndex(messages: List<ChatHistoryMessage>, key: String?, leading: Int): Int? {
        if (key == null) return null
        val position = messages.indexOfFirst { it.key == key }
        if (position < 0) return null
        return messages.lastIndex - position + leading
    }

    fun scrollOffset(itemHeight: Int, viewportHeight: Int): Int = itemHeight - viewportHeight

    private fun epochSeconds(timestamp: String): Long? =
        runCatching { Instant.parse(timestamp).epochSecond }.getOrNull()
}
