package dev.multiprompt.companion.model

import dev.multiprompt.companion.data.WorkspaceStore

data class RecentFolder(
    val hostId: String,
    val hostLabel: String,
    val path: String,
    val uses: Int = 0,
    val lastActivityEpochSeconds: Long = 0L,
) {
    val name: String
        get() = path.trimEnd('/').substringAfterLast('/').ifBlank { path }
}

object RecentFolders {
    const val MAX_FOLDERS = 200

    fun collect(
        hosts: List<HostProfile>,
        sessions: List<TmuxSession>,
        dissolved: List<DissolvedSession>,
        usageByHost: Map<String, Map<String, Int>>,
        limit: Int = MAX_FOLDERS,
    ): List<RecentFolder> {
        val labels = hosts.associate { it.id to it.label }
        val merged = LinkedHashMap<Pair<String, String>, RecentFolder>()

        fun add(hostId: String, rawPath: String, uses: Int, activity: Long, rollUp: Boolean) {
            val label = labels[hostId] ?: return
            val path = normalize(rawPath, rollUp) ?: return
            val key = hostId to path
            val known = merged[key]
            merged[key] = if (known == null) {
                RecentFolder(hostId, label, path, uses, activity)
            } else {
                known.copy(
                    uses = maxOf(known.uses, uses),
                    lastActivityEpochSeconds = maxOf(known.lastActivityEpochSeconds, activity),
                )
            }
        }

        sessions.forEach { add(it.hostId, it.workingDirectory, 0, it.lastActivityEpochSeconds, rollUp = true) }
        dissolved.forEach { add(it.hostId, it.workingDirectory, 0, it.dissolvedAtEpochSeconds, rollUp = true) }
        usageByHost.forEach { (hostId, usage) ->
            usage.forEach { (path, uses) -> add(hostId, path, uses, 0L, rollUp = false) }
        }

        val hostOrder = hosts.withIndex().associate { it.value.id to it.index }
        return merged.values
            .sortedWith(
                compareByDescending<RecentFolder> { it.lastActivityEpochSeconds }
                    .thenByDescending { it.uses }
                    .thenBy { it.name.lowercase() }
                    .thenBy { hostOrder[it.hostId] ?: Int.MAX_VALUE }
                    .thenBy { it.path },
            )
            .take(limit.coerceAtLeast(0))
    }

    fun filter(folders: List<RecentFolder>, query: String): List<RecentFolder> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return folders
        return folders
            .filter { folder ->
                val haystack = "${folder.name} ${folder.hostLabel} ${folder.path}".lowercase()
                words.all { it in haystack }
            }
            .sortedBy { folder ->
                val name = folder.name.lowercase()
                if (words.any { name.startsWith(it) }) 0 else 1
            }
    }

    private fun normalize(rawPath: String, rollUp: Boolean): String? {
        val trimmed = rawPath.trim()
        if (!trimmed.startsWith('/')) return null
        val path = if (rollUp) WorkspaceStore.projectRoot(trimmed) else trimmed.trimEnd('/')
        return path?.takeIf { it.length > 1 && it.startsWith('/') }
    }
}
