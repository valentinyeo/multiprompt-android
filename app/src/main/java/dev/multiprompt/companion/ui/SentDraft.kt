package dev.multiprompt.companion.ui

import dev.multiprompt.companion.skills.Skill

internal data class SentDraft(
    val prompt: String,
    val images: List<String>,
    val skill: Skill?,
    val replyTo: String?,
) {
    fun restoredPrompt(current: String): String = when {
        current.isBlank() -> prompt
        prompt.isBlank() -> current
        else -> prompt.trimEnd() + "\n" + current
    }

    fun restoredImages(current: List<String>): List<String> = images + current.filter { it !in images }
}
