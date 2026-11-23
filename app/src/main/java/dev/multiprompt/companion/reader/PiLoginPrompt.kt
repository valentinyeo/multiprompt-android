package dev.multiprompt.companion.reader

object PiLoginPrompt {
    data class Prompt(val text: String, val link: String?)

    data class Active(val index: Int, val prompt: Prompt)

    const val MAX_TEXT = 4000

    private val LINK = Regex("https?://[^\\s<>()\\[\\]{}`\"']+")
    private val CUE = Regex(
        "(?i)\\b(?:sign[ -]?in|log[ -]?in|login|paste the code|device code|verification code|" +
            "access code|open (?:this|the) link|authenticate|authori[sz]e)\\b",
    )
    private val DIALOG = Regex("(?i)^(?:paste the code|enter the code|pi needs an answer\\.?|pi opened a login link\\.?)$")
    private val LIST = Regex("(?i)^log in to a pi provider:?")
    private val CONFIRM = Regex("(?i)^logged in to ([a-z0-9][a-z0-9._-]{0,63})\\.\\s*the next reply uses that login\\.?$")
    private val LOST = Regex("(?i)^(?:pi login failed\\b|that pi provider id is not valid|login (?:failed|expired|cancelled)\\b)")

    fun detect(text: String): Prompt? {
        val body = text.trim()
        if (body.isEmpty() || body.length > MAX_TEXT) return null
        val link = link(body)
        if (CUE.containsMatchIn(body.replace(LINK, " "))) return Prompt(body, link)
        return if (link == null && (DIALOG.matches(body) || LIST.containsMatchIn(body))) Prompt(body, null) else null
    }

    fun link(text: String): String? = LINK.find(text)?.value?.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '`', '"', '\'')

    fun confirmsLogin(text: String): String? = CONFIRM.find(text.trim())?.groupValues?.get(1)?.lowercase()

    fun lapsed(text: String): Boolean = LOST.containsMatchIn(text.trim())

    fun current(messages: List<String>): Active? {
        var active: Active? = null
        messages.forEachIndexed { index, text ->
            when {
                confirmsLogin(text) != null || lapsed(text) -> active = null
                else -> detect(text)?.let { active = Active(index, it) }
            }
        }
        return active
    }
}
