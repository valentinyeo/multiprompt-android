package dev.multiprompt.companion.ui

import dev.multiprompt.companion.dictation.DictationStatus

internal object DictationDraft {
    data class Before(val text: String, val appendBase: String) {
        fun withSpoken(spoken: String): String = PromptComposer.appendDictation(appendBase, spoken)

        fun restored(): String = text
    }

    fun captured(prompt: String): Before = Before(text = prompt, appendBase = prompt.trimEnd())

    fun spokenPrompt(before: Before, transcript: String, status: DictationStatus, canceled: Boolean): String? {
        val spoken = transcript.trim()
        if (canceled || !status.active || spoken.isBlank()) return null
        return before.withSpoken(spoken)
    }
}
