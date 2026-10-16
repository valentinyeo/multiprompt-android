package dev.multiprompt.companion.model

object RefreshMerge {
    const val SHOW_SAVED_SESSIONS_WHILE_CONNECTING_IGSH_340 = true

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
}
