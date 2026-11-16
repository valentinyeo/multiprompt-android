package dev.multiprompt.companion.ui

import dev.multiprompt.companion.reader.ChatMention

object ChatFind {
    data class Hit(val message: Int, val start: Int, val end: Int)

    fun find(messages: List<String>, query: String): List<Hit> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val hits = ArrayList<Hit>()
        messages.forEachIndexed { message, text ->
            if (text.length < needle.length) return@forEachIndexed
            val last = text.length - needle.length
            var index = 0
            while (index <= last) {
                if (text.regionMatches(index, needle, 0, needle.length, ignoreCase = true)) {
                    hits += Hit(message, index, index + needle.length)
                }
                index++
            }
        }
        return hits
    }

    fun step(selected: Int, count: Int, previous: Boolean): Int {
        if (count <= 0) return 0
        val current = selected.coerceIn(0, count - 1)
        return if (previous) {
            if (current == 0) count - 1 else current - 1
        } else {
            (current + 1) % count
        }
    }

    fun label(selected: Int, count: Int): String {
        val shown = if (count <= 0) 0 else selected.coerceIn(0, count - 1) + 1
        return "$shown of $count"
    }

    fun display(role: String, text: String): String {
        val shown = ChatMention.visible(text)
        return if (role == "user") shown else MarkdownParser.visibleText(shown)
    }
}
