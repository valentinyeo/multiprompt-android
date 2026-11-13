package dev.multiprompt.companion.ui

import dev.multiprompt.companion.reader.ChatMention

object LiveMention {
    fun visible(text: String): String = ChatMention.visible(text)
}
