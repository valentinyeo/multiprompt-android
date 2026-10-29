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

    @Test fun onlyDesktopPaletteIndicesAreAccepted() {
        for (color in 0..6) assertEquals(color, layout(color).json().getJSONArray("workspaces").getJSONObject(0).getInt("color"))
        for (color in listOf(-1, 7, 1.5, "1", true, JSONObject.NULL)) {
            val json = layout().json().apply { getJSONArray("workspaces").getJSONObject(0).put("color", color) }
            assertThrows(Exception::class.java) { AccountLayout.parse(json) }
        }
    }
}
