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
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return directories
            .asSequence()
            .filter { dir -> words.all { dir.name.lowercase().contains(it) } }
            .map { RankedDirectory(it.name, it.isRepo, usage[it.name] ?: 0) }
            .sortedWith(
                compareBy<RankedDirectory> { if (startsWithAWord(it.name, words)) 0 else 1 }
                    .thenByDescending { it.uses }
                    .thenBy { it.name.lowercase() },
            )
            .toList()
    }

    private fun startsWithAWord(name: String, words: List<String>): Boolean {
        val lower = name.substringAfterLast('/').lowercase()
        return words.any { lower.startsWith(it) }
    }
}
