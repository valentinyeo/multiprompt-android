package dev.multiprompt.companion.ui

/** Small, deterministic helpers for composing text in the native prompt field. */
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

    fun composeMessage(prompt: String, imageUrls: List<String>): String = listOf(
        imageUrls.joinToString("\n") { it.trim() }.trim(),
        prompt.trim(),
    ).filter(String::isNotBlank).joinToString("\n")
}
