package dev.multiprompt.companion.data

import android.app.Application
import android.content.Context
import dev.multiprompt.companion.AppUiState
import dev.multiprompt.companion.account.FAVORITES_SPLIT_ID
import dev.multiprompt.companion.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkspaceArchiveStoreTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        context.getSharedPreferences("workspaces", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun workspace(name: String) = Workspace(id = name, name = name, hostId = "", remotePath = "")

    private fun state(selected: String?, archived: Set<String> = emptySet()) = AppUiState(
        workspaces = listOf(workspace("Work"), workspace("Home")),
        workspaceSplitIds = listOf(null, "Work", "Home"),
        archivedWorkspaceIds = archived,
        selectedWorkspaceId = selected,
    )

    @Test fun aFreshInstallHasNothingArchived() {
        assertEquals(emptySet<String>(), WorkspaceStore(context).archivedIds())
    }

    @Test fun archiveAddsAndRestoreRemoves() {
        val store = WorkspaceStore(context)
        assertEquals(setOf("Work"), store.archive("Work"))
        assertEquals(setOf("Work", "Home"), store.archive("Home"))
        assertEquals(setOf("Home"), store.restore("Work"))
        assertEquals(emptySet<String>(), store.restore("Home"))
    }

    @Test fun archivingTwiceKeepsOneEntryAndRestoringAnUnknownIdChangesNothing() {
        val store = WorkspaceStore(context)
        store.archive("Work")
        assertEquals(setOf("Work"), store.archive("Work"))
        assertEquals(setOf("Work"), store.restore("Missing"))
    }

    @Test fun archivedWorkspacesSurviveANewStoreInstance() {
        WorkspaceStore(context).archive("Work")
        assertEquals(setOf("Work"), WorkspaceStore(context).archivedIds())
        WorkspaceStore(context).restore("Work")
        assertEquals(emptySet<String>(), WorkspaceStore(context).archivedIds())
    }

    @Test fun archivingTouchesNeitherTheSavedSelectionNorTheOtherWorkspaces() {
        val store = WorkspaceStore(context)
        store.saveSelectedSplitId("Home")
        store.upsert(workspace("Work"))
        store.archive("Work")
        assertEquals("Home", store.selectedSplitId())
        assertEquals(listOf("Work"), store.load().map { it.id })
    }

    @Test fun anArchivedWorkspaceLeavesTheStripAndThePickersAndShowsInTheArchivedList() {
        val shown = state(selected = null, archived = setOf("Work"))
        assertFalse("Work" in shown.visibleWorkspaceSplitIds)
        assertTrue("Home" in shown.visibleWorkspaceSplitIds)
        assertEquals(listOf("Home"), shown.activeWorkspaces.map { it.id })
        assertEquals(listOf("Work"), shown.archivedWorkspaces.map { it.id })
    }

    @Test fun restoringPutsTheWorkspaceBackInTheStrip() {
        val restored = state(selected = null, archived = setOf("Work")).withArchivedWorkspaceIds(emptySet())
        assertTrue("Work" in restored.visibleWorkspaceSplitIds)
        assertEquals(emptyList<Workspace>(), restored.archivedWorkspaces)
    }

    @Test fun archivingTheSelectedWorkspaceFallsBackToAVisibleOne() {
        val next = state(selected = "Work").withArchivedWorkspaceIds(setOf("Work"))
        assertFalse(next.selectedWorkspaceId == "Work")
        assertTrue(next.selectedWorkspaceId in next.visibleWorkspaceSplitIds)
    }

    @Test fun archivingAnotherWorkspaceKeepsTheSelection() {
        assertEquals("Home", state(selected = "Home").withArchivedWorkspaceIds(setOf("Work")).selectedWorkspaceId)
        assertEquals(FAVORITES_SPLIT_ID, state(selected = FAVORITES_SPLIT_ID).withArchivedWorkspaceIds(setOf("Work")).selectedWorkspaceId)
    }

    @Test fun restoringKeepsTheCurrentSelection() {
        assertEquals("Home", state(selected = "Home", archived = setOf("Work")).withArchivedWorkspaceIds(emptySet()).selectedWorkspaceId)
    }
}
