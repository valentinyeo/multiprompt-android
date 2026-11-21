package dev.multiprompt.companion.model

object RefreshMerge {
    data class HostResult(
        val sessions: List<TmuxSession> = emptyList(),
        val error: String? = null,
    )

    data class Merged(
        val sessions: List<TmuxSession>,
        val cachedHostIds: Set<String>,
        val updatingHostIds: Set<String>,
    )

    fun merge(
        hostIds: List<String>,
        currentSessions: List<TmuxSession>,
        cachedByHost: Map<String, List<TmuxSession>>,
        results: Map<String, HostResult>,
        final: Boolean = false,
        currentUpdatingHostIds: Set<String> = emptySet(),
    ): Merged {
        val currentByHost = currentSessions.groupBy { it.hostId }
        val cachedHostIds = mutableSetOf<String>()
        val updatingHostIds = mutableSetOf<String>()
        val sessions = hostIds.flatMap { hostId ->
            val result = results[hostId]
            when {
                result == null -> {
                    val current = currentByHost[hostId].orEmpty()
                    val saved = cachedByHost[hostId].orEmpty()
                    val showingSaved = hostId in currentUpdatingHostIds || (current.isEmpty() && saved.isNotEmpty())
                    if (!final && showingSaved) updatingHostIds.add(hostId)
                    current.ifEmpty { saved }
                }
                result.error == null -> result.sessions
                else -> {
                    val fallback = cachedByHost[hostId].orEmpty().ifEmpty { currentByHost[hostId].orEmpty() }
                    if (fallback.isNotEmpty()) cachedHostIds.add(hostId)
                    fallback
                }
            }
        }
        return Merged(sessions, cachedHostIds, updatingHostIds)
    }

    fun stabilizeHostedIdentity(
        previous: List<TmuxSession>,
        incoming: List<TmuxSession>,
        hostListFinal: Boolean,
    ): List<TmuxSession> =
        incoming.map { session ->
            val prior = previous.firstOrNull { it.hostId == session.hostId && it.name == session.name } ?: return@map session
            if (!session.liveChat || session.hostProvider.isBlank()) return@map session
            if (prior.hostSessionId.isBlank() || session.hostSessionId.isBlank()) return@map session
            if (session.hostSessionId == prior.hostSessionId || session.hostProvider != prior.hostProvider) return@map session
            if (hostListFinal) session
            else session.copy(hostSessionId = prior.hostSessionId)
        }
}
