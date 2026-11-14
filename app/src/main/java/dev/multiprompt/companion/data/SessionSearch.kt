package dev.multiprompt.companion.data

import dev.multiprompt.companion.account.AccountHostMap
import dev.multiprompt.companion.account.AccountLayout
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import java.util.Locale

object SessionSearch {
    fun newestFirst(
        sessions: List<TmuxSession>,
        interactionEpochSeconds: Map<String, Long> = emptyMap(),
        layout: AccountLayout? = null,
        mapping: Map<Int, HostProfile> = emptyMap(),
    ): List<TmuxSession> = sessions
        .map { session -> session to rank(session, interactionEpochSeconds, layout, mapping) }
        .sortedWith(
            compareByDescending<Pair<TmuxSession, Rank>> { it.second.used }
                .thenBy { it.second.position }
                .thenByDescending { SessionReadStore.waitingForReply(it.first) }
                .thenBy { it.first.workspaceName }
                .thenComparator { a, b -> compareHostSessionId(a.first, b.first) }
                .thenBy { it.first.hostId }
                .thenBy { it.first.name },
        )
        .map { it.first }

    private fun compareHostSessionId(a: TmuxSession, b: TmuxSession): Int {
        val na = a.hostSessionId.toLongOrNull()
        val nb = b.hostSessionId.toLongOrNull()
        return if (na != null && nb != null) na.compareTo(nb) else a.hostSessionId.compareTo(b.hostSessionId)
    }

    private data class Rank(val used: Long, val position: Int)

    private fun rank(
        session: TmuxSession,
        interactionEpochSeconds: Map<String, Long>,
        layout: AccountLayout?,
        mapping: Map<Int, HostProfile>,
    ): Rank {
        val tab = layout?.let { AccountHostMap.tabFor(session, it, mapping) }
        val layoutUsed = (tab?.used ?: 0L) / 1000
        val localUsed = interactionEpochSeconds[SessionReadStore.key(session.hostId, session.name)] ?: 0L
        val position = if (layout != null && tab != null) layout.tabs.indexOf(tab) else Int.MAX_VALUE
        return Rank(maxOf(localUsed, layoutUsed), position)
    }

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
