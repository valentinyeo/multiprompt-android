package dev.multiprompt.companion.ui

import dev.multiprompt.companion.model.TmuxSession

internal object SessionRowLine {
    fun line(session: TmuxSession): String {
        val fallback = SessionPreview.line(session.preview)
        val parts = session.rowParts?.takeIf { it.isCurrentFor(session) } ?: return fallback
        if (parts.question.isNotEmpty()) return parts.question
        val working = fallback.startsWith("Working:") || session.replyWaiting == false
        if (working) {
            return if (fallback.startsWith("Working:")) fallback else parts.step.ifEmpty { parts.reply.ifEmpty { fallback } }
        }
        return parts.reply.ifEmpty { fallback }
    }
}
