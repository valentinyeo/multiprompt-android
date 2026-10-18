package dev.multiprompt.companion.account

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FavoriteLayoutTest {
    private fun layout(favorite: Boolean? = null, name: String = "One", revision: Int = 1): JSONObject {
        val json = JSONObject("""{"schema":1,"revision":$revision,"workspaces":[{"name":"Other"}],"tabs":[{"kind":"tmux","name":"$name","tmux_session":"one","tmux_host":0,"workspace":"Other"}]}""")
        favorite?.let { json.getJSONArray("tabs").getJSONObject(0).put("favorite", it) }
        return json
    }

    @Test fun starredServerResponseRoundTrips() {
        val parsed = AccountLayout.response(JSONObject().put("revision", 1).put("layout", layout(true)))
        assertEquals(true, parsed.json().getJSONArray("tabs").getJSONObject(0).get("favorite"))
        assertEquals(parsed, AccountLayout.parse(JSONObject(parsed.json().toString())))
    }

    @Test fun unstarredServerResponseRoundTrips() {
        val parsed = AccountLayout.response(JSONObject().put("revision", 1).put("layout", layout(false)))
        assertEquals(false, parsed.json().getJSONArray("tabs").getJSONObject(0).get("favorite"))
        assertEquals(parsed, AccountLayout.parse(JSONObject(parsed.json().toString())))
    }

    @Test fun absentFavoriteStaysAbsent() {
        val parsed = AccountLayout.parse(layout())
        assertFalse(parsed.json().getJSONArray("tabs").getJSONObject(0).has("favorite"))
        assertEquals(parsed, AccountLayout.parse(JSONObject(parsed.json().toString())))
    }

    @Test fun localFavoriteToggleAndRemoteRenameBothSurvivePull() {
        for (favorite in listOf(true, false)) {
            val base = AccountLayout.parse(layout(!favorite))
            val edited = AccountLayout.parse(layout(favorite))
            val remote = AccountLayout.parse(layout(!favorite, "Laptop rename", 2))
            val pulled = PendingLayout(base, edited).pull(remote)
            val tab = pulled.edited.json().getJSONArray("tabs").getJSONObject(0)
            assertEquals("Laptop rename", tab.getString("name"))
            assertEquals(favorite, tab.get("favorite"))
            assertTrue(pulled.dirty)
        }
    }

    @Test fun remoteFavoriteSurvivesPullWhenPhoneOnlyRenamed() {
        for (favorite in listOf(true, false)) {
            val base = AccountLayout.parse(layout())
            val edited = AccountLayout.parse(layout(name = "Phone rename"))
            val remote = AccountLayout.parse(layout(favorite, revision = 2))
            val tab = PendingLayout(base, edited).pull(remote).edited.json().getJSONArray("tabs").getJSONObject(0)
            assertEquals("Phone rename", tab.getString("name"))
            assertEquals(favorite, tab.get("favorite"))
        }
    }
}
