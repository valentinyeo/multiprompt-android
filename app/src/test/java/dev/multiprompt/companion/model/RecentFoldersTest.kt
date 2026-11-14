package dev.multiprompt.companion.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentFoldersTest {
    private val vps = HostProfile(id = "vps", label = "VPS", hostname = "a.example.com", username = "u")
    private val hetzner = HostProfile(id = "hz", label = "Hetzner", hostname = "b.example.com", username = "u")
    private val hosts = listOf(vps, hetzner)

    private fun session(host: String, name: String, cwd: String, activity: Long) = TmuxSession(
        hostId = host,
        name = name,
        windows = 1,
        attachedClients = 0,
        lastActivityEpochSeconds = activity,
        workingDirectory = cwd,
    )

    private fun dissolved(host: String, cwd: String, at: Long) = DissolvedSession(
        hostId = host,
        tmuxSessionName = "gone-$cwd",
        displayName = "gone",
        agent = AgentKind.CLAUDE,
        workingDirectory = cwd,
        resumeCommand = "",
        dissolvedAtEpochSeconds = at,
    )

    @Test
    fun listsFoldersFromEveryServerNewestFirst() {
        val folders = RecentFolders.collect(
            hosts,
            listOf(
                session("vps", "a", "/home/u/projects/alpha", 100),
                session("hz", "b", "/home/u/projects/funeralprints", 300),
                session("vps", "c", "/home/u/projects/beta", 200),
            ),
            emptyList(),
            emptyMap(),
        )

        assertEquals(listOf("funeralprints", "beta", "alpha"), folders.map { it.name })
        assertEquals(listOf("Hetzner", "VPS", "VPS"), folders.map { it.hostLabel })
        assertEquals(listOf("hz", "vps", "vps"), folders.map { it.hostId })
    }

    @Test
    fun sessionsInsideOneProjectCollapseToItsRoot() {
        val folders = RecentFolders.collect(
            hosts,
            listOf(
                session("vps", "a", "/home/u/projects/alpha/apps/web", 100),
                session("vps", "b", "/home/u/projects/alpha", 400),
                session("vps", "c", "/home/u/projects/alpha/", 50),
            ),
            emptyList(),
            emptyMap(),
        )

        assertEquals(1, folders.size)
        assertEquals("/home/u/projects/alpha", folders.single().path)
        assertEquals(400L, folders.single().lastActivityEpochSeconds)
    }

    @Test
    fun sameFolderOnTwoServersStaysTwoRows() {
        val folders = RecentFolders.collect(
            hosts,
            listOf(
                session("vps", "a", "/home/u/projects/site", 100),
                session("hz", "b", "/home/u/projects/site", 100),
            ),
            emptyList(),
            emptyMap(),
        )

        assertEquals(listOf("vps", "hz"), folders.map { it.hostId })
    }

    @Test
    fun usageAndDissolvedSessionsAddFoldersAndUses() {
        val folders = RecentFolders.collect(
            hosts,
            listOf(session("vps", "a", "/home/u/projects/alpha", 100)),
            listOf(dissolved("hz", "/home/u/projects/old", 500)),
            mapOf(
                "vps" to mapOf("/home/u/projects/alpha" to 4, "/home/u/projects/chosen" to 2),
                "hz" to mapOf("/srv/app/" to 1),
            ),
        )

        val byPath = folders.associateBy { it.hostId to it.path }
        assertEquals(4, byPath.getValue("vps" to "/home/u/projects/alpha").uses)
        assertEquals(100L, byPath.getValue("vps" to "/home/u/projects/alpha").lastActivityEpochSeconds)
        assertEquals(2, byPath.getValue("vps" to "/home/u/projects/chosen").uses)
        assertEquals(500L, byPath.getValue("hz" to "/home/u/projects/old").lastActivityEpochSeconds)
        assertEquals("app", byPath.getValue("hz" to "/srv/app").name)
        assertEquals("hz", folders.first().hostId)
    }

    @Test
    fun equalActivityOrdersByUsesThenName() {
        val folders = RecentFolders.collect(
            hosts,
            emptyList(),
            emptyList(),
            mapOf("vps" to mapOf("/p/zeta" to 3, "/p/alpha" to 3, "/p/busy" to 9)),
        )

        assertEquals(listOf("busy", "alpha", "zeta"), folders.map { it.name })
    }

    @Test
    fun dropsBlankRootRelativeAndUnknownServerPaths() {
        val folders = RecentFolders.collect(
            hosts,
            listOf(
                session("vps", "a", "", 100),
                session("vps", "b", "/", 100),
                session("vps", "c", "relative/dir", 100),
                session("gone", "d", "/home/u/projects/ghost", 100),
            ),
            emptyList(),
            mapOf("vps" to mapOf("" to 3, "/" to 2)),
        )

        assertTrue(folders.isEmpty())
    }

    @Test
    fun listIsCapped() {
        val sessions = (1..30).map { session("vps", "s$it", "/p/dir$it", it.toLong()) }
        val folders = RecentFolders.collect(hosts, sessions, emptyList(), emptyMap(), limit = 10)

        assertEquals(10, folders.size)
        assertEquals("dir30", folders.first().name)
    }

    @Test
    fun blankQueryKeepsTheOrder() {
        val folders = listOf(
            RecentFolder("vps", "VPS", "/p/zeta"),
            RecentFolder("hz", "Hetzner", "/p/alpha"),
        )

        assertEquals(folders, RecentFolders.filter(folders, "  "))
    }

    @Test
    fun typingFiltersAcrossNameServerAndPath() {
        val folders = listOf(
            RecentFolder("vps", "VPS", "/home/u/projects/multiprompt"),
            RecentFolder("hz", "Hetzner", "/home/u/projects/funeralprints"),
            RecentFolder("hz", "Hetzner", "/home/u/projects/notes"),
        )

        assertEquals(listOf("funeralprints"), RecentFolders.filter(folders, "funeral").map { it.name })
        assertEquals(listOf("funeralprints", "notes"), RecentFolders.filter(folders, "hetz").map { it.name })
        assertEquals(listOf("notes"), RecentFolders.filter(folders, "HETZNER note").map { it.name })
        assertEquals(listOf("multiprompt"), RecentFolders.filter(folders, "/multi").map { it.name })
        assertTrue(RecentFolders.filter(folders, "nothing").isEmpty())
    }

    @Test
    fun nameMatchesRankAheadOfPathMatches() {
        val folders = listOf(
            RecentFolder("vps", "VPS", "/home/site/projects/other"),
            RecentFolder("vps", "VPS", "/home/u/projects/site-tools"),
        )

        assertEquals(listOf("site-tools", "other"), RecentFolders.filter(folders, "site").map { it.name })
    }

    @Test
    fun nameIsTheLastPathSegment() {
        assertEquals("alpha", RecentFolder("vps", "VPS", "/home/u/projects/alpha").name)
        assertEquals("alpha", RecentFolder("vps", "VPS", "/home/u/projects/alpha/").name)
    }
}
