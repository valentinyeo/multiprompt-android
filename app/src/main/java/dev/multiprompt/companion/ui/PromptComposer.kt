package dev.multiprompt.companion.ui

internal object PromptComposer {
    fun appendImageUrl(prompt: String, url: String): String {
        return appendImageUrls(prompt, listOf(url))
    }

    fun appendImageUrls(prompt: String, urls: List<String>): String {
        val combined = listOf(prompt.trimEnd()) + urls.map(String::trim)
            .filter(String::isNotBlank)
        val text = combined.filter(String::isNotBlank).joinToString("\n")
        return if (text.isBlank()) "" else "$text "
    }

    fun appendDictation(prompt: String, transcript: String): String = listOf(
        prompt.trimEnd(),
        transcript.trim(),
    ).filter(String::isNotBlank).joinToString(" ")

    fun composeMessage(
        prompt: String,
        imageUrls: List<String>,
        replyTo: String?,
        nativeSkills: List<dev.multiprompt.companion.skills.NativeSkill>,
    ): String {
        val text = prompt.trim()
        val command = text.takeWhile { !it.isWhitespace() }
        val skill = nativeSkills.firstOrNull { command == "/${it.name}" }
        val arguments = if (skill == null) text else text.removePrefix("/${skill.name}").trimStart()
        val body = listOf(
            imageUrls.joinToString("\n") { it.trim() }.trim(),
            arguments,
        ).filter(String::isNotBlank).joinToString("\n")
        val message = ReplyQuote.compose(replyTo, body)
        return if (skill == null) message else "/${skill.name} $message".trimEnd()
    }
}
