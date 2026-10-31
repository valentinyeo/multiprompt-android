package dev.multiprompt.companion.reader

object ReadAloud {
    private val fenced = Regex("```[\\s\\S]*?```")

    fun replies(messages: List<ChatHistoryMessage>): List<String> =
        messages.asReversed()
            .filter { it.role == "assistant" && !it.pending }
            .map { it.text.trim() }
            .filter(::speakable)

    fun next(replies: List<String>, index: Int): Pair<String, Int>? {
        if (replies.isEmpty()) return null
        val position = index.mod(replies.size)
        return replies[position] to position + 1
    }

    private fun speakable(text: String): Boolean =
        fenced.replace(text, " ").any(Char::isLetterOrDigit)
}
