package dev.multiprompt.companion.reader

object ReadAloud {
    private val fenced = Regex("```[\\s\\S]*?```")

    data class Spoken(val key: String, val text: String)

    fun replies(messages: List<ChatHistoryMessage>): List<Spoken> =
        messages.asReversed()
            .filter { it.role == "assistant" && !it.pending }
            .map { Spoken(it.key, it.text.trim()) }
            .filter { speakable(it.text) }

    fun next(replies: List<Spoken>, index: Int): Pair<Spoken, Int>? {
        if (replies.isEmpty()) return null
        val position = index.mod(replies.size)
        return replies[position] to position + 1
    }

    private fun speakable(text: String): Boolean =
        fenced.replace(text, " ").any(Char::isLetterOrDigit)
}
