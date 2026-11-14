package dev.multiprompt.companion.data

import dev.multiprompt.companion.model.TmuxSession

object UnreadOnlyFilter {
    fun visible(
        sessions: List<TmuxSession>,
        unreadKeys: Set<String>,
        enabled: Boolean,
        searching: Boolean = false,
    ): List<TmuxSession> =
        if (!enabled || searching) sessions
        else sessions.filter { SessionReadStore.key(it.hostId, it.name) in unreadKeys }
}
