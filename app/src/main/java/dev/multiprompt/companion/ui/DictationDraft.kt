package dev.multiprompt.companion.ui

internal object DictationDraft {
    data class Before(val text: String, val appendBase: String) {
        fun withSpoken(spoken: String): String = PromptComposer.appendDictation(appendBase, spoken)

        fun restored(): String = text
    }

    fun captured(prompt: String): Before = Before(text = prompt, appendBase = prompt.trimEnd())
}
