package dev.multiprompt.companion.account

import dev.multiprompt.companion.AppUiState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WorkspaceArchiveTest {
    private val one = AccountTab("One", "one", 0, "Product", favorite = true)
    private val two = AccountTab("Two", "two", 1, "Other")
    private val three = AccountTab("Three", "three", 2, "Product")
    private val layout = AccountLayout(
        revision = 4,
        workspaces = listOf("Main", "Other", "Product"),
        tabs = listOf(one, two, three),
        workspaceColors = mapOf("Product" to 2),
    )

    @Test fun archiveRemovesTheWorkspaceAndItsTabsAndStillValidates() {
        val result = WorkspaceArchive.archive(layout, emptyList(), "Product")
        assertEquals(listOf("Main", "Other"), result.layout.workspaces)
        assertEquals(listOf("two"), result.layout.tabs.map { it.session })
        assertTrue(result.layout.workspaceColors.isEmpty())
        assertEquals(result.layout, result.layout.validate())
        assertEquals(listOf(ArchivedWorkspace("Product", 2, listOf(one, three))), result.archived)
        assertEquals(result.layout, AccountLayout.parse(result.layout.json()))
    }

    @Test fun restoreBringsBackTheWorkspaceTabsAndColour() {
        val archived = WorkspaceArchive.archive(layout, emptyList(), "Product")
        val restored = WorkspaceArchive.restore(archived.layout, archived.archived, "Product")
        assertEquals(setOf("Main", "Other", "Product"), restored.layout.workspaces.toSet())
        assertEquals(setOf("one", "two", "three"), restored.layout.tabs.map { it.session }.toSet())
        assertEquals(2, restored.layout.workspaceColors["Product"])
        assertEquals(true, restored.layout.tabs.first { it.session == "one" }.favorite)
        assertTrue(restored.archived.isEmpty())
    }

    @Test fun restoreSkipsSessionsThatMovedElsewhereAndRefusesATakenName() {
        val archived = WorkspaceArchive.archive(layout, emptyList(), "Product")
        val moved = archived.layout.addTab(one.copy(workspace = "Other"))
        val restored = WorkspaceArchive.restore(moved, archived.archived, "Product")
        assertEquals("Other", restored.layout.tabs.first { it.session == "one" }.workspace)
        assertEquals(1, restored.layout.tabs.count { it.session == "one" })
        val reused = archived.layout.copy(workspaces = archived.layout.workspaces + "Product")
        assertThrows(IllegalArgumentException::class.java) { WorkspaceArchive.restore(reused, archived.archived, "Product") }
    }

    @Test fun deleteWorksOnlyForAnArchivedWorkspace() {
        assertThrows(IllegalArgumentException::class.java) { WorkspaceArchive.delete(emptyList(), "Product") }
        val archived = WorkspaceArchive.archive(layout, emptyList(), "Product").archived
        assertTrue(WorkspaceArchive.delete(archived, "Product").isEmpty())
        assertThrows(IllegalArgumentException::class.java) { WorkspaceArchive.delete(archived, "Other") }
    }

    @Test fun mainAndMissingWorkspacesCannotBeArchived() {
        assertThrows(IllegalArgumentException::class.java) { WorkspaceArchive.archive(layout, emptyList(), "Main") }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceArchive.archive(layout, emptyList(), "main") }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceArchive.archive(layout, emptyList(), "Missing") }
    }

    @Test fun archivedWorkspaceSurvivesStorageRoundTrip() {
        val entry = ArchivedWorkspace("Product", 2, listOf(one, three))
        assertEquals(entry, ArchivedWorkspace.parse(JSONObject(entry.json().toString())))
        val bare = ArchivedWorkspace("Empty")
        assertEquals(bare, ArchivedWorkspace.parse(JSONObject(bare.json().toString())))
    }

    @Test fun archiveStaysArchivedAfterTheNextPull() {
        val pending = PendingLayout(layout)
        val archived = WorkspaceArchive.archive(pending.edited, emptyList(), "Product")
        val edited = pending.edit(archived.layout)
        val pulled = edited.pull(layout.copy(revision = 5))
        assertEquals(listOf("Main", "Other"), pulled.edited.workspaces)
        assertEquals(listOf("two"), pulled.edited.tabs.map { it.session })
    }

    @Test fun emptyMainIsHiddenUnlessSelectedOrInUse() {
        assertTrue(WorkspaceArchive.isHiddenMain("Main", "Product", listOf("Product")))
        assertFalse(WorkspaceArchive.isHiddenMain("Main", "Main", emptyList()))
        assertFalse(WorkspaceArchive.isHiddenMain("Main", "Product", listOf("Main")))
        assertFalse(WorkspaceArchive.isHiddenMain("Product", "Other", emptyList()))
        assertFalse(WorkspaceArchive.isHiddenMain(null, null, emptyList()))
    }

    @Test fun splitListDropsAnEmptyMainOnly() {
        val state = AppUiState(
            workspaceSplitIds = listOf(null, "Main", "Product"),
            selectedWorkspaceId = "Product",
            sessionWorkspaceIds = mapOf("a" to "Product"),
        )
        assertFalse("Main" in state.visibleWorkspaceSplitIds)
        assertTrue("Product" in state.visibleWorkspaceSplitIds)
        val used = state.copy(sessionWorkspaceIds = mapOf("a" to "Main"))
        assertTrue("Main" in used.visibleWorkspaceSplitIds)
        val selected = state.copy(selectedWorkspaceId = "Main")
        assertTrue("Main" in selected.visibleWorkspaceSplitIds)
    }
}
