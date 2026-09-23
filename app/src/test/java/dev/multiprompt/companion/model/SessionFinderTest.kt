package dev.multiprompt.companion.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionFinderTest {

    private fun session(
        name: String,
        attached: Int = 0,
        activity: Long = 0,
        directory: String = "/projects/$name",
        title: String = "",
    ) = TmuxSession(
        hostId = "host",
        name = name,
        windows = 1,
        attachedClients = attached,
        lastActivityEpochSeconds = activity,
        workingDirectory = directory,
        title = title,
    )

    @Test
    fun needsAttentionHidesAttachedAndAlreadyOpenSessions() {
        val ranked = SessionFinder.rank(
            sessions = listOf(
                session("attached", attached = 1, activity = 10),
                session("open", activity = 9),
                session("detached", activity = 8),
            ),
            isOpen = { it.name == "open" },
            needsAttentionOnly = true,
            query = "",
        )

        assertEquals(listOf("detached"), ranked.map { it.name })
    }

    @Test
    fun switchOffShowsEverythingNewestFirst() {
        val ranked = SessionFinder.rank(
            sessions = listOf(
                session("older", activity = 1),
                session("newer", activity = 5),
                session("attached", attached = 1, activity = 9),
            ),
            isOpen = { false },
            needsAttentionOnly = false,
            query = "",
        )

        assertEquals(listOf("attached", "newer", "older"), ranked.map { it.name })
    }

    @Test
    fun typingMatchesNameTitleAndFolder() {
        val ranked = SessionFinder.rank(
            sessions = listOf(
                session(
                    "strix",
                    activity = 3,
                    directory = "/home/valentin/projects/hypertask",
                    title = "Review Strix security setup",
                ),
                session("other", activity = 4, directory = "/srv/other"),
            ),
            isOpen = { false },
            needsAttentionOnly = false,
            query = "SECURITY",
        )

        assertEquals(listOf("strix"), ranked.map { it.name })
    }
}