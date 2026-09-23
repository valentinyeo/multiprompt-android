package dev.multiprompt.companion.model

/**
 * Ranking and filtering for the existing-session picker.
 *
 * A session "needs attention" when no tmux client is attached and this app is not already
 * showing it. That is the set someone searching for a session to reconnect actually wants
 * first; the already connected ones are one switch away and open instantly.
 */
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