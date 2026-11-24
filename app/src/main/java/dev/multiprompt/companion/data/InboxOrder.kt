package dev.multiprompt.companion.data

import dev.multiprompt.companion.model.TmuxSession

object InboxOrder {
    fun display(sessions: List<TmuxSession>, pinnedKeys: List<String>, newestAtBottom: Boolean): List<TmuxSession> =
        PinnedChats.order(sessions, pinnedKeys, atEnd = newestAtBottom)

    fun topToBottom(sessions: List<TmuxSession>, pinnedKeys: List<String>, newestAtBottom: Boolean): List<TmuxSession> =
        display(sessions, pinnedKeys, newestAtBottom).let { if (newestAtBottom) it.asReversed() else it }

    fun <T> nextAfterRemoving(items: List<T>, removedIndex: Int): T? {
        if (removedIndex !in items.indices) return items.firstOrNull()
        val remaining = items.filterIndexed { index, _ -> index != removedIndex }
        return remaining.getOrNull(removedIndex) ?: remaining.lastOrNull()
    }
}
