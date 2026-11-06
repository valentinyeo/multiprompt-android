package dev.multiprompt.companion.model

object RenameCommand {
    private val pattern = Regex("""^/rename\s+(\S.*)$""", RegexOption.DOT_MATCHES_ALL)

    fun parse(prompt: String): String? =
        pattern.matchEntire(prompt.trim())?.groupValues?.get(1)?.trim()?.takeIf { '\n' !in it }
}
