package dev.multiprompt.companion.reader

object PiLoginPrompt {
    data class Prompt(val text: String, val link: String)

    data class Active(val index: Int, val prompt: Prompt)

    private const val TRAILING = ".,;:!?)]}`\"'"

    private val LINK = Regex("https://[^\\s<>()\\[\\]{}`\"']+")
    private val HOST_ROW = Regex("pi-login-[0-9]+")

    private fun hostRow(message: ChatHistoryMessage): Boolean =
        message.role == "assistant" && message.hostItem?.let(HOST_ROW::matches) == true

    fun prompt(message: ChatHistoryMessage): Prompt? {
        if (!hostRow(message)) return null
        return link(message.text)?.let { Prompt(message.text.trim(), it) }
    }

    fun link(text: String): String? = LINK.find(text)?.value?.trimEnd(*TRAILING.toCharArray())

    fun current(messages: List<ChatHistoryMessage>): Active? {
        val index = messages.indexOfLast(::hostRow)
        if (index < 0) return null
        return prompt(messages[index])?.let { Active(index, it) }
    }
}
