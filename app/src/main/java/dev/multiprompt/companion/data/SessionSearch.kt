package dev.multiprompt.companion.data

import dev.multiprompt.companion.model.TmuxSession
import java.util.Locale

object SessionSearch {
    fun newestFirst(sessions: List<TmuxSession>): List<TmuxSession> =
        sessions.sortedByDescending { it.lastActivityEpochSeconds }

    fun matches(
        session: TmuxSession,
        query: String,
        hostLabel: String = "",
        workspaceName: String = "",
    ): Boolean {
        val terms = query
            .trim()
            .lowercase(Locale.ROOT)
            .split(WHITESPACE)
            .filter(String::isNotBlank)
        if (terms.isEmpty()) return true

        val searchable = listOf(
            session.displayName,
            session.name,
            session.title,
            session.preview,
            session.workingDirectory,
            session.agent.label,
            hostLabel,
            workspaceName,
        ).joinToString("\n").lowercase(Locale.ROOT)
        return terms.all { term -> term in searchable }
    }

    private val WHITESPACE = Regex("\\s+")
}
