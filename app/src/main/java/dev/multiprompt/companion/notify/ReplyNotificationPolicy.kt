package dev.multiprompt.companion.notify

import dev.multiprompt.companion.reader.ChatHistory
import java.text.BreakIterator
import java.util.Locale

object ReplyNotificationPolicy {
    fun text(history: ChatHistory): String? {
        val question = history.pendingQuestions.firstOrNull()?.title
        val message = history.messages.lastOrNull()
        val reply = question ?: message?.takeIf { it.role == "assistant" }?.text ?: return null
        val text = reply.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty() || text.startsWith("/") || text.startsWith("<")) return null
        val sentences = BreakIterator.getSentenceInstance(Locale.ENGLISH)
        sentences.setText(text)
        return text.substring(0, sentences.next().takeUnless { it == BreakIterator.DONE } ?: text.length).trim()
    }

    data class Plan(val notify: List<String>, val cancel: Set<String>)

    fun plan(
        baselined: Boolean,
        replied: Set<String>,
        current: Map<String, Int>,
        frontKey: String?,
        active: Set<String>,
        favorites: Set<String>,
    ): Plan {
        val cancel = active.filterTo(linkedSetOf()) { key -> key !in favorites || key == frontKey || (current[key] ?: 0) <= 0 }
        if (!baselined) return Plan(emptyList(), cancel)
        val notify = replied
            .filter { key -> key in favorites && key != frontKey && (current[key] ?: 0) > 0 }
            .sorted()
        return Plan(notify, cancel)
    }
}
