package dev.multiprompt.companion.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshMergeTest {
    private val hostIds = listOf("first", "second")
    private val firstCached = session("first", "saved-first")
    private val secondCached = session("second", "saved-second")
    private val cachedByHost = mapOf("first" to listOf(firstCached), "second" to listOf(secondCached))

    @Test
    fun zeroResultsShowSavedSessionsForEveryHostAsUpdatingNotCached() {
        val merged = RefreshMerge.merge(hostIds, emptyList(), cachedByHost, emptyMap())

        assertEquals(listOf(firstCached, secondCached), merged.sessions)
        assertEquals(hostIds.toSet(), merged.updatingHostIds)
        assertTrue(merged.cachedHostIds.isEmpty())
    }

    @Test
    fun oneHostAnswerReplacesOnlyItsSavedSessions() {
        val initial = RefreshMerge.merge(hostIds, emptyList(), cachedByHost, emptyMap())
        val fresh = session("second", "fresh-second")
        val merged = RefreshMerge.merge(
            hostIds,
            initial.sessions,
            cachedByHost,
            mapOf("second" to RefreshMerge.HostResult(listOf(fresh))),
            currentUpdatingHostIds = initial.updatingHostIds,
        )

        assertEquals(listOf(firstCached, fresh), merged.sessions)
        assertEquals(setOf("first"), merged.updatingHostIds)
        assertTrue(merged.cachedHostIds.isEmpty())
    }

    @Test
    fun failedHostKeepsSavedSessionsAndIsCachedNotUpdating() {
        val merged = RefreshMerge.merge(
            hostIds,
            emptyList(),
            cachedByHost,
            mapOf("first" to RefreshMerge.HostResult(error = "SSH failed")),
        )

        assertEquals(listOf(firstCached, secondCached), merged.sessions)
        assertEquals(setOf("first"), merged.cachedHostIds)
        assertEquals(setOf("second"), merged.updatingHostIds)
    }

    @Test
    fun unansweredHostPrefersCurrentSessionsOverSavedSessions() {
        val current = session("first", "current-first")
        val merged = RefreshMerge.merge(hostIds, listOf(current), cachedByHost, emptyMap())

        assertEquals(listOf(current, secondCached), merged.sessions)
        assertEquals(setOf("second"), merged.updatingHostIds)
        assertTrue(merged.cachedHostIds.isEmpty())
    }

    @Test
    fun periodicRefreshOfShownSessionsIsNotMarkedUpdating() {
        val merged = RefreshMerge.merge(hostIds, listOf(firstCached, secondCached), cachedByHost, emptyMap())

        assertEquals(listOf(firstCached, secondCached), merged.sessions)
        assertTrue(merged.updatingHostIds.isEmpty())
    }

    @Test
    fun successfulEmptyAnswerRemovesOldSessions() {
        val merged = RefreshMerge.merge(
            hostIds,
            listOf(firstCached, secondCached),
            cachedByHost,
            mapOf("first" to RefreshMerge.HostResult()),
        )

        assertEquals(listOf(secondCached), merged.sessions)
        assertTrue(merged.updatingHostIds.isEmpty())
        assertTrue(merged.cachedHostIds.isEmpty())
    }

    @Test
    fun failedHostPrefersSavedSessionsOverCurrentSessions() {
        val merged = RefreshMerge.merge(
            hostIds,
            listOf(session("first", "current-first")),
            cachedByHost,
            mapOf("first" to RefreshMerge.HostResult(error = "SSH failed")),
        )

        assertEquals(listOf(firstCached, secondCached), merged.sessions)
        assertEquals(setOf("first"), merged.cachedHostIds)
    }

    @Test
    fun failedHostUsesCurrentSessionsWhenSavedSessionsAreEmpty() {
        val current = session("first", "current-first")
        val merged = RefreshMerge.merge(
            hostIds,
            listOf(current),
            mapOf("first" to emptyList(), "second" to listOf(secondCached)),
            mapOf("first" to RefreshMerge.HostResult(error = "SSH failed")),
        )

        assertEquals(listOf(current, secondCached), merged.sessions)
        assertEquals(setOf("first"), merged.cachedHostIds)
        assertEquals(setOf("second"), merged.updatingHostIds)
    }

    @Test
    fun failedHostWithNoSessionsIsNeitherCachedNorUpdating() {
        val merged = RefreshMerge.merge(
            hostIds,
            emptyList(),
            emptyMap(),
            mapOf("first" to RefreshMerge.HostResult(error = "SSH failed")),
        )

        assertTrue(merged.sessions.isEmpty())
        assertTrue(merged.cachedHostIds.isEmpty())
        assertTrue(merged.updatingHostIds.isEmpty())
    }

    @Test
    fun unansweredHostsWithoutSavedSessionsAreNotMarkedUpdating() {
        val merged = RefreshMerge.merge(hostIds, emptyList(), emptyMap(), emptyMap())

        assertTrue(merged.sessions.isEmpty())
        assertTrue(merged.cachedHostIds.isEmpty())
        assertTrue(merged.updatingHostIds.isEmpty())
    }

    @Test
    fun finalMergeKeepsSnapshotOrderAndClearsUpdatingHosts() {
        val fresh = session("second", "fresh-second")
        val merged = RefreshMerge.merge(
            hostIds,
            emptyList(),
            cachedByHost,
            linkedMapOf(
                "second" to RefreshMerge.HostResult(listOf(fresh)),
                "first" to RefreshMerge.HostResult(error = "SSH failed"),
            ),
            final = true,
        )

        assertEquals(listOf(firstCached, fresh), merged.sessions)
        assertEquals(setOf("first"), merged.cachedHostIds)
        assertTrue(merged.updatingHostIds.isEmpty())
    }

    private fun session(hostId: String, name: String) = TmuxSession(
        hostId = hostId,
        name = name,
        windows = 1,
        attachedClients = 0,
        lastActivityEpochSeconds = 1,
    )
}
