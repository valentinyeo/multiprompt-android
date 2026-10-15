package dev.multiprompt.companion.model

data class RemoteDirectory(
    val name: String,
    val isRepo: Boolean,
)

data class RemoteListing(
    val root: String,
    val directories: List<RemoteDirectory>,
)

data class RankedDirectory(
    val name: String,
    val isRepo: Boolean,
    val uses: Int,
)

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