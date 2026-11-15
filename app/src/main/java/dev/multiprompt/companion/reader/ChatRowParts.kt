package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.RowParts
import dev.multiprompt.companion.model.TmuxSession

object ChatRowParts {
    private const val MAX_LENGTH = 200
    private val whitespace = Regex("\\s+")

    fun from(session: TmuxSession, history: ChatHistory): RowParts = RowParts(
        question = firstLine(history.pendingQuestions.firstOrNull()?.title.orEmpty()),
        step = firstLine(history.step),
        reply = firstLine(history.messages.lastOrNull { it.role == "assistant" }?.text.orEmpty()),
        activityEpochSeconds = session.lastActivityEpochSeconds,
        replyEpochSeconds = session.replyActivityEpochSeconds,
    )

    fun firstLine(text: String): String {
        val line = text.lineSequence()
            .map { it.replace("*", "").replace("`", "").trim().trimStart('#', '>', '-', ' ').trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        return line.replace(whitespace, " ").take(MAX_LENGTH)
    }
}
