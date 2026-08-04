package dev.multiprompt.companion.data

import dev.multiprompt.companion.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceStoreTest {
    @Test
    fun derivesRepositoryRootFromNestedProjectPath() {
        assertEquals(
            "/home/valentin/projects/hypertasks",
            WorkspaceStore.projectRoot("/home/valentin/projects/hypertasks/apps/web"),
        )
    }

    @Test
    fun keepsNonProjectDirectoryAsWorkspaceRoot() {
        assertEquals("/srv/agent", WorkspaceStore.projectRoot("/srv/agent/"))
    }

    @Test
    fun ordersSplitsBySessionCountByDefault() {
        val quiet = workspace("quiet", "Quiet")
        val busy = workspace("busy", "Busy")

        assertEquals(
            listOf("busy", "quiet"),
            WorkspaceStore.orderWorkspaces(
                listOf(quiet, busy),
                mapOf("busy" to 7, "quiet" to 1),
                manualOrder = null,
            ).map { it.id },
        )
    }

    @Test
    fun manualSplitOrderOverridesSessionCounts() {
        val quiet = workspace("quiet", "Quiet")
        val busy = workspace("busy", "Busy")

        assertEquals(
            listOf("quiet", "busy"),
            WorkspaceStore.orderWorkspaces(
                listOf(quiet, busy),
                mapOf("busy" to 7, "quiet" to 1),
                manualOrder = listOf("quiet", "busy"),
            ).map { it.id },
        )
    }

    @Test
    fun automaticSplitOrderStartsWithAllThenUsesLatestActivity() {
        val stale = workspace("stale", "Stale")
        val recent = workspace("recent", "Recent")

        assertEquals(
            listOf(null, "recent", "stale"),
            WorkspaceStore.orderSplitIds(
                listOf(stale, recent),
                mapOf("recent" to 200L, "stale" to 100L),
                manualOrder = null,
            ),
        )
    }

    @Test
    fun manualSplitOrderCanMoveAll() {
        val quiet = workspace("quiet", "Quiet")
        val busy = workspace("busy", "Busy")

        assertEquals(
            listOf("quiet", null, "busy"),
            WorkspaceStore.orderSplitIds(
                listOf(quiet, busy),
                mapOf("busy" to 200L, "quiet" to 100L),
                manualOrder = listOf("quiet", "__all_sessions__", "busy"),
            ),
        )
    }

    @Test
    fun legacyManualOrderMigratesWithAllFirst() {
        val quiet = workspace("quiet", "Quiet")
        val busy = workspace("busy", "Busy")

        assertEquals(
            listOf(null, "quiet", "busy"),
            WorkspaceStore.orderSplitIds(
                listOf(quiet, busy),
                emptyMap(),
                manualOrder = listOf("quiet", "busy"),
            ),
        )
    }

    private fun workspace(id: String, name: String) = Workspace(
        id = id,
        name = name,
        hostId = "host",
        remotePath = "/projects/$id",
    )
}
