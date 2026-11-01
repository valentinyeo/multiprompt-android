package dev.multiprompt.companion.account

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceColorsTest {
    private fun layout(first: Int? = null, second: Int? = null): AccountLayout {
        val json = AccountLayout(workspaces = listOf("Cedar", "Orbit")).json()
        first?.let { json.getJSONArray("workspaces").getJSONObject(0).put("color", it) }
        second?.let { json.getJSONArray("workspaces").getJSONObject(1).put("color", it) }
        return AccountLayout.parse(json)
    }

    @Test fun desktopColorsSurviveOfflineStorageAndUnrelatedEdits() {
        val original = layout(4, 6)
        val edited = original.renameWorkspace("Cedar", "Lagoon").setSetting("frequent", true)
        val restored = PendingLayout.parse(PendingLayout(original, edited).json()).edited.json()
        assertEquals(4, restored.getJSONArray("workspaces").getJSONObject(0).getInt("color"))
        assertEquals("Lagoon", restored.getJSONArray("workspaces").getJSONObject(0).getString("name"))
        assertEquals(6, restored.getJSONArray("workspaces").getJSONObject(1).getInt("color"))
        assertFalse(layout().json().getJSONArray("workspaces").getJSONObject(0).has("color"))
    }

    @Test fun remotePaletteChangesSurvivePendingLocalEdits() {
        val base = layout(1, 2)
        val edited = base.setSetting("frequent", true)
        val remote = layout(5, 6).copy(revision = 2)
        val merged = AccountLayout.rebase(base, edited, remote)
        assertEquals(5, merged.json().getJSONArray("workspaces").getJSONObject(0).getInt("color"))
        assertEquals(6, merged.json().getJSONArray("workspaces").getJSONObject(1).getInt("color"))
        assertEquals(true, merged.settings?.get("frequent"))
    }

    @Test fun explicitColorResetAndUnrelatedRemoteColorBothSurviveRebase() {
        val base = layout(1, 2)
        val edited = base.copy(workspaceColors = mapOf("Orbit" to 4))
        val merged = AccountLayout.rebase(base, edited, layout(5, 6))
        assertFalse(merged.json().getJSONArray("workspaces").getJSONObject(0).has("color"))
        assertEquals(4, merged.workspaceColor("Orbit"))
        assertFalse(base.json(includeWorkspaceColors = false).getJSONArray("workspaces").getJSONObject(0).has("color"))
        assertEquals(base, AccountLayout.parse(base.json()))
        assertFalse(base.sameContent(edited))
    }

    @Test fun renamedWorkspaceKeepsConcurrentRemoteColorAfterPersistenceAndSequentialRenames() {
        val base = layout(1, 2)
        val pending = PendingLayout(base).renameWorkspace("Cedar", "Lagoon")
            .renameWorkspace("Lagoon", "Harbor")
        val restored = PendingLayout.parse(JSONObject(pending.json().toString()))
        assertEquals(pending, restored)
        assertFalse(restored.edited.json().has("workspaceRenames"))
        val remote = layout(6, 4).copy(revision = 2, settings = mapOf("all" to true))
        val pulled = restored.pull(remote)
        assertEquals(listOf("Harbor", "Orbit"), pulled.edited.workspaces)
        assertEquals(mapOf("Harbor" to 6, "Orbit" to 4), pulled.edited.workspaceColors)
        assertEquals(remote.settings, pulled.edited.settings)
        assertTrue(pulled.dirty)
        val again = PendingLayout.parse(JSONObject(pulled.json().toString()))
            .pull(remote.copy(revision = 3, workspaceColors = mapOf("Cedar" to 5, "Orbit" to 3)))
        assertEquals(mapOf("Harbor" to 5, "Orbit" to 3), again.edited.workspaceColors)
    }

    @Test fun explicitLocalColorWinsWhileOtherRemoteChangesSurvive() {
        val base = layout(1, 2)
        val renamed = PendingLayout(base).renameWorkspace("Cedar", "Lagoon")
        val pending = renamed.edit(renamed.edited.copy(workspaceColors = mapOf("Lagoon" to 4, "Orbit" to 2)))
        val result = pending.pull(layout(6, 5))
        assertEquals(mapOf("Lagoon" to 4, "Orbit" to 5), result.edited.workspaceColors)
        val reset = renamed.edit(renamed.edited.copy(workspaceColors = mapOf("Orbit" to 2)))
        assertEquals(mapOf("Orbit" to 5), reset.pull(layout(6, 5)).edited.workspaceColors)
    }

    @Test fun renameDuringUploadRebasesFromSentName() {
        val base = layout(1, 2)
        val sent = PendingLayout(base).renameWorkspace("Cedar", "Lagoon")
        val later = sent.renameWorkspace("Lagoon", "Harbor")
        val remote = sent.edited.copy(revision = 2, workspaceColors = mapOf("Lagoon" to 6, "Orbit" to 5))
        val acknowledged = later.acknowledge(sent, remote)
        assertEquals(mapOf("Harbor" to 6, "Orbit" to 5), acknowledged.edited.workspaceColors)
        assertEquals(mapOf("Lagoon" to "Harbor"), acknowledged.workspaceRenames)
        assertTrue(acknowledged.dirty)
        val done = acknowledged.acknowledge(acknowledged, acknowledged.edited.copy(revision = 3))
        assertFalse(done.dirty)
        assertTrue(done.workspaceRenames.isEmpty())
    }

    @Test fun renameBackDuringUploadKeepsDirtySemanticsAndRemoteColor() {
        val base = layout(1, 2)
        val sent = PendingLayout(base).renameWorkspace("Cedar", "Lagoon")
        val later = sent.renameWorkspace("Lagoon", "Cedar")
        assertFalse(later.dirty)
        val restored = PendingLayout.parse(JSONObject(later.json().toString()))
        val remote = sent.edited.copy(revision = 2, workspaceColors = mapOf("Lagoon" to 6, "Orbit" to 5))
        val result = restored.acknowledge(sent, remote)
        assertEquals(mapOf("Cedar" to 6, "Orbit" to 5), result.edited.workspaceColors)
        assertTrue(result.dirty)
    }

    @Test fun legacyPendingWithoutRenameMetadataRemainsReadable() {
        val base = layout(1, 2)
        val legacy = JSONObject().put("base", base.json()).put("edited", base.setSetting("all", true).json())
        val restored = PendingLayout.parse(legacy)
        assertTrue(restored.workspaceRenames.isEmpty())
        assertTrue(restored.dirty)
        assertEquals(mapOf("Cedar" to 6, "Orbit" to 5), restored.pull(layout(6, 5)).edited.workspaceColors)
        assertTrue(base.sameContent(base.copy(revision = 9)))
        assertFalse(PendingLayout(base).dirty)
    }

    @Test fun accountOrderAndOverridesReachExistingAndOfflineSessions() {
        val host = dev.multiprompt.companion.model.HostProfile("host", "VPS", "example.com", username = "user", keySecretId = "key")
        val tab = AccountTab("One", "one", 0, "Orbit")
        val current = layout(null, 6).copy(tabs = listOf(tab))
        val session = AccountHostMap.sessionFor(tab, emptyList(), mapOf(0 to host), current)!!
        assertEquals(6, session.workspaceColor)
        assertEquals(6, AccountHostMap.withAccountSessions(listOf(session), current, mapOf(0 to host)).single().workspaceColor)
        assertEquals(1, layout().workspaceColor("Orbit"))
        assertEquals(0, layout().workspaceColor("Cedar"))
        assertEquals(0, AccountLayout(workspaces = (0..8).map { "Workspace$it" }).workspaceColor("Workspace7"))
    }

    @Test fun onlyDesktopPaletteIndicesAreAccepted() {
        for (color in 0..10) assertEquals(color, layout(color).json().getJSONArray("workspaces").getJSONObject(0).getInt("color"))
        for (color in listOf(-1, 11, 1.5, "1", true, JSONObject.NULL)) {
            val json = layout().json().apply { getJSONArray("workspaces").getJSONObject(0).put("color", color) }
            assertThrows(Exception::class.java) { AccountLayout.parse(json) }
        }
    }
}
