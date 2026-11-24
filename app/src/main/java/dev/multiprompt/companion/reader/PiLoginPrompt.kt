package dev.multiprompt.companion.reader

object PiLoginPrompt {
    data class Prompt(val text: String, val link: String?)

    data class Active(val index: Int, val prompt: Prompt)

    const val MAX_TEXT = 4000

    private const val MAX_LINES = 3
    private const val MAX_LINE = 300
    private const val TRAILING = ".,;:!?)]}`\"'"

    private val LINK = Regex("https://[^\\s<>()\\[\\]{}`\"']+")
    private val SIGN_IN = Regex(
        "(?i)\\b(?:sign[ -]?in|log[ -]?in|login|paste the code|device code|verification code|" +
            "access code|open (?:this|the|following) (?:link|url)|authenticate|authori[sz]e)\\b",
    )
    private val DIALOG = Regex("(?i)^(?:paste the code|enter the code|pi needs an answer\\.?|pi opened a login link\\.?)$")
    private val LIST = Regex("(?i)^log in to a pi provider:?")
    private val CONFIRM = Regex("(?i)^logged in to ([a-z0-9][a-z0-9._-]{0,63})\\.\\s*the next reply uses that login\\.?$")
    private val LOST = Regex("(?i)^(?:pi login failed\\b|that pi provider id is not valid|login (?:failed|expired|cancelled)\\b)")

    fun prompt(message: ChatHistoryMessage): Prompt? =
        if (message.role == "assistant") detect(message.text) else null

    fun detect(text: String): Prompt? {
        val body = text.trim()
        if (body.isEmpty() || body.length > MAX_TEXT) return null
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val link = link(body)
        val found = when {
            lines.isEmpty() -> false
            LIST.containsMatchIn(lines.first()) -> true
            lines.any { DIALOG.matches(it) } -> true
            link == null -> false
            else -> linkPrompt(lines, link)
        }
        return if (found) Prompt(body, link) else null
    }

    private fun linkPrompt(lines: List<String>, link: String): Boolean {
        if (lines.size > MAX_LINES || lines.any { it.length > MAX_LINE }) return false
        val words = lines.filter { it.trimEnd(*TRAILING.toCharArray()) != link }
        return if (words.isEmpty()) lines.size == 1 else words.any { SIGN_IN.containsMatchIn(it) }
    }

    fun link(text: String): String? = LINK.find(text)?.value?.trimEnd(*TRAILING.toCharArray())

    fun confirmsLogin(text: String): String? = CONFIRM.find(text.trim())?.groupValues?.get(1)?.lowercase()

    fun lapsed(text: String): Boolean = LOST.containsMatchIn(text.trim())

    fun current(messages: List<ChatHistoryMessage>): Active? {
        var active: Active? = null
        messages.forEachIndexed { index, message ->
            if (message.role != "assistant") return@forEachIndexed
            when {
                confirmsLogin(message.text) != null || lapsed(message.text) -> active = null
                else -> prompt(message)?.let { active = Active(index, it) }
            }
        }
        return active
    }
}
