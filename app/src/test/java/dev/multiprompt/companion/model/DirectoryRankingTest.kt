package dev.multiprompt.companion.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectoryRankingTest {
    @Test
    fun mostUsedFirstThenAlphabetical() {
        val ranked = DirectoryRanking.rank(
            listOf(
                RemoteDirectory("zeta", isRepo = true),
                RemoteDirectory("alpha", isRepo = true),
                RemoteDirectory("beta", isRepo = false),
                RemoteDirectory("busy", isRepo = true),
            ),
            usage = mapOf("zeta" to 3, "busy" to 3, "alpha" to 1),
            query = "",
        )

        assertEquals(listOf("busy", "zeta", "alpha", "beta"), ranked.map { it.name })
    }

    @Test
    fun typingNarrowsToTheRepo() {
        val ranked = DirectoryRanking.rank(
            listOf(
                RemoteDirectory("multiprompt-android", isRepo = true),
                RemoteDirectory("hypertasks", isRepo = true),
            ),
            usage = emptyMap(),
            query = "PROMPT",
        )

        assertEquals(listOf("multiprompt-android"), ranked.map { it.name })
    }

    @Test
    fun carriesUseCountsAndRepoFlag() {
        val ranked = DirectoryRanking.rank(
            listOf(RemoteDirectory("api", isRepo = false)),
            usage = mapOf("api" to 4),
            query = "api",
        )

        assertEquals(1, ranked.size)
        assertEquals(4, ranked.single().uses)
        assertEquals(false, ranked.single().isRepo)
    }

    @Test
    fun partialWordsMatchInEitherOrderAndNamePrefixComesFirst() {
        val dirs = listOf(
            RemoteDirectory("old-soft-factory-notes", isRepo = false),
            RemoteDirectory("software-factory", isRepo = false),
            RemoteDirectory("hypertasks", isRepo = true),
        )
        val usage = mapOf("old-soft-factory-notes" to 9)

        assertEquals(
            listOf("software-factory", "old-soft-factory-notes"),
            DirectoryRanking.rank(dirs, usage, "soft").map { it.name },
        )
        assertEquals(
            listOf("software-factory", "old-soft-factory-notes"),
            DirectoryRanking.rank(dirs, usage, "factory soft").map { it.name },
        )
        assertEquals(emptyList<String>(), DirectoryRanking.rank(dirs, usage, "soft tasks").map { it.name })
    }
}
