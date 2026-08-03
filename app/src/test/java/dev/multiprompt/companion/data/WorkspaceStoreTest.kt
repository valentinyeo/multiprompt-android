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

    private fun workspace(id: String, name: String) = Workspace(
        id = id,
        name = name,
        hostId = "host",
        remotePath = "/projects/$id",
    )
}
