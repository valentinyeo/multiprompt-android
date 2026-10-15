package dev.multiprompt.companion.model

object SessionFinder {

    fun needsAttention(session: TmuxSession, isOpen: (TmuxSession) -> Boolean): Boolean =
        session.attachedClients <= 0 && !isOpen(session)

    fun rank(
        sessions: List<TmuxSession>,
        isOpen: (TmuxSession) -> Boolean,
        needsAttentionOnly: Boolean,
        query: String,
    ): List<TmuxSession> {
        val needle = query.trim().lowercase()
        return sessions
            .asSequence()
            .filter { !needsAttentionOnly || needsAttention(it, isOpen) }
            .filter { session ->
                needle.isEmpty() ||
                    session.name.lowercase().contains(needle) ||
                    session.displayName.lowercase().contains(needle) ||
                    session.workingDirectory.lowercase().contains(needle)
            }
            .sortedWith(
                compareByDescending<TmuxSession> { it.lastActivityEpochSeconds }
                    .thenBy { it.displayName.lowercase() },
            )
            .toList()
    }
}