package dev.multiprompt.companion.reader

object ChatReactions {
    enum class Emoji(val text: String) {
        LIKE("👍"), DISLIKE("👎"), HEART("❤️"),
    }

    data class Reaction(val emoji: Emoji, val reference: String, val quote: String)
    data class Presentation(
        val messages: List<ChatHistoryMessage>,
        val badges: Map<String, Set<Emoji>>,
    )

    fun reference(id: String): String {
        if (id.isEmpty()) return "0000000000000000"
        var hash = -3750763034362895579L
        for (byte in id.toByteArray(Charsets.UTF_8)) hash = (hash xor (byte.toLong() and 255)) * 1099511628211L
        return hash.toULong().toString(16).padStart(16, '0')
    }

    private fun normalized(text: String): String = text.replace(Regex("[\\s\\u001c-\\u0020\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]+"), " ").trim()

    fun format(emoji: Emoji, requestId: String, text: String): String? {
        val body = normalized(text)
        if (body.isEmpty()) return null
        val length = minOf(body.length, 80).let { if (it < body.length && body[it - 1].isHighSurrogate()) it - 1 else it }
        val quote = body.take(length).trimEnd()
        return "${emoji.text} to: $quote [mp:${reference(requestId)}]"
    }

    fun parse(text: String): Reaction? {
        val emoji = Emoji.entries.firstOrNull { text.startsWith(it.text + " to: ") } ?: return null
        val body = text.removePrefix(emoji.text + " to: ")
        if (body.length < 23 || !body.endsWith(']')) return null
        val quote = body.dropLast(22)
        val suffix = body.takeLast(22)
        if (quote.isEmpty() || quote.length > 80 || normalized(quote) != quote || !suffix.startsWith(" [mp:")) return null
        val reference = suffix.substring(5, 21)
        if (!reference.matches(Regex("[0-9a-f]{16}"))) return null
        return Reaction(emoji, reference, quote)
    }

    fun present(messages: List<ChatHistoryMessage>, collapsePending: Boolean = true): Presentation {
        val targets = messages.withIndex().filter { it.value.role == "assistant" && it.value.requestId.isNotEmpty() }
            .groupBy { reference(it.value.requestId) }
        val badges = linkedMapOf<String, MutableSet<Emoji>>()
        fun apply(text: String, before: Int): Boolean {
            val reaction = parse(text) ?: return false
            if (reaction.reference == "0000000000000000") return false
            val target = targets[reaction.reference]?.singleOrNull() ?: return false
            if (target.index >= before || !normalized(target.value.text).contains(reaction.quote)) return false
            badges.getOrPut(target.value.key) { linkedSetOf() }.add(reaction.emoji)
            return true
        }
        val reactions = mutableSetOf<Int>()
        val displayed = messages.mapIndexed { index, message ->
            if (message.role != "user" || (message.pending && !collapsePending)) return@mapIndexed message
            val lines = message.text.lines()
            val kept = lines.filterNot { apply(it, index) }
            if (kept.size == lines.size) return@mapIndexed message
            val text = kept.joinToString("\n").trim('\n')
            if (text.isBlank()) reactions.add(index)
            message.copy(text = text)
        }
        val silent = hideReplies(messages, reactions)
        val visible = displayed.filterIndexed { index, _ -> index !in reactions && index !in silent }
        return Presentation(visible, badges)
    }

    private fun hideReplies(messages: List<ChatHistoryMessage>, reactions: Set<Int>): Set<Int> {
        val hidden = mutableSetOf<Int>()
        for (reaction in reactions) {
            val replies = mutableListOf<Int>()
            var index = reaction + 1
            while (index < messages.size && messages[index].role != "user") {
                if (messages[index].role == "assistant") replies.add(index)
                index++
            }
            if (replies.size == 1) hidden.add(replies.single())
        }
        return hidden
    }
}
