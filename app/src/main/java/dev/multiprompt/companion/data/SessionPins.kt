package dev.multiprompt.companion.data

import dev.multiprompt.companion.model.TmuxSession

object SessionPins {
    fun arrange(sessions: List<TmuxSession>, pinned: Set<String>, pinnedLast: Boolean): List<TmuxSession> {
        if (pinned.isEmpty()) return sessions
        val (top, rest) = sessions.partition { SessionReadStore.key(it.hostId, it.name) in pinned }
        return if (pinnedLast) rest + top else top + rest
    }
}
