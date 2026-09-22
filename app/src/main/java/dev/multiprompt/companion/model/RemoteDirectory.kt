package dev.multiprompt.companion.model

/** A folder offered by the remote directory listing. */
data class RemoteDirectory(
    val name: String,
    val isRepo: Boolean,
)

/** The folders inside one remote directory, plus the absolute path that was listed. */
data class RemoteListing(
    val root: String,
    val directories: List<RemoteDirectory>,
)

/** A folder in the order the picker shows it. */
data class RankedDirectory(
    val name: String,
    val isRepo: Boolean,
    /** How many sessions this app has already started in that folder. */
    val uses: Int,
)

/**
 * Ranking and filtering for the folder picker. Most used first, then alphabetical, so the repo
 * someone works in daily is one tap away and typing a few letters narrows the list to it.
 */
object DirectoryRanking {

    fun rank(
        directories: List<RemoteDirectory>,
        usage: Map<String, Int>,
        query: String,
    ): List<RankedDirectory> {
        val needle = query.trim().lowercase()
        return directories
            .asSequence()
            .filter { needle.isEmpty() || it.name.lowercase().contains(needle) }
            .map { RankedDirectory(it.name, it.isRepo, usage[it.name] ?: 0) }
            .sortedWith(
                compareByDescending<RankedDirectory> { it.uses }
                    .thenBy { it.name.lowercase() },
            )
            .toList()
    }
}