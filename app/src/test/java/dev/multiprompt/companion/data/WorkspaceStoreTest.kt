package dev.multiprompt.companion.data

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
}
