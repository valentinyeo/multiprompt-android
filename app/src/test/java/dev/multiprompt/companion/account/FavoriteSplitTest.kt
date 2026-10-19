package dev.multiprompt.companion.account

import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FavoriteSplitTest {
    private val host = HostProfile("v", "Hetzner", "vps", username = "valentin", keySecretId = "key")
    private val mapping = mapOf(0 to host, 1 to host.copy(id = "c"))
    private val session = TmuxSession(host.id, "one", 1, 0, 0)
    private val tab = AccountTab("One", "one", 0, "Other")
    private val layout = AccountLayout(1, listOf("Other", "Product"), listOf(tab, tab.copy(session = "two")))

    @Test fun onlyExplicitTrueIsFavorite() {
        assertFalse(isFavoriteSession(session, null, mapping))
        assertFalse(isFavoriteSession(session, layout, mapping))
        assertFalse(isFavoriteSession(session, layout.setFavorite(tab.identity, false), mapping))
        val starred = layout.setFavorite(tab.identity, true)
        assertTrue(isFavoriteSession(session, starred, mapping))
        assertTrue(isFavoriteSession(session.copy(name = "ONE"), starred, mapping))
        assertFalse(isFavoriteSession(session.copy(name = "two"), starred, mapping))
        assertFalse(isFavoriteSession(session.copy(name = "missing"), starred, mapping))
    }

    @Test fun favoritesRespectHostIdentityAndMapping() {
        val starred = layout.setFavorite(tab.identity, true)
        assertFalse(isFavoriteSession(session, starred, emptyMap()))
        assertFalse(isFavoriteSession(session.copy(hostId = "c"), starred, mapping))
        assertFalse(isFavoriteSession(session.copy(hostId = "agent"), starred, mapping))
        val sessions = listOf(session, session.copy(hostId = "c"), session.copy(name = "two"))
        assertEquals(listOf(session), sessions.filter { isFavoriteSession(it, starred, mapping) })
    }

    @Test fun toggleChangesOnlyTargetFavoriteAndDoesNotInventOtherValues() {
        val starred = layout.setFavorite(tab.identity, true)
        assertEquals(layout.tabs[0].copy(favorite = true), starred.tabs[0])
        assertEquals(layout.tabs[1], starred.tabs[1])
        assertFalse(starred.json().getJSONArray("tabs").getJSONObject(1).has("favorite"))
        val unstarred = starred.setFavorite(tab.identity, false)
        assertEquals(false, unstarred.tabs[0].favorite)
        assertEquals(false, unstarred.json().getJSONArray("tabs").getJSONObject(0).get("favorite"))
        assertEquals(layout, layout.setFavorite("0:missing", true))
        assertNull(layout.tabs[0].favorite)
    }

    @Test fun optionalManualAndFavoriteKeysParseInEveryCombination() {
        for (manual in listOf(null, false, true)) {
            for (favorite in listOf(null, false, true)) {
                val json = layout.json()
                val t = json.getJSONArray("tabs").getJSONObject(0)
                t.remove("manual")
                manual?.let { t.put("manual", it) }
                favorite?.let { t.put("favorite", it) }
                val parsed = AccountLayout.parse(json)
                assertEquals(manual ?: false, parsed.tabs[0].manual)
                assertEquals(favorite, parsed.tabs[0].favorite)
                assertEquals(parsed, AccountLayout.parse(parsed.json()))
            }
        }
    }

    @Test fun favoriteMustBeBooleanAndUnknownOrMissingKeysStillFailClosed() {
        for (value in listOf("true", 1, 0, JSONObject.NULL, JSONObject())) {
            val json = layout.json()
            json.getJSONArray("tabs").getJSONObject(0).put("favorite", value)
            assertThrows(IllegalArgumentException::class.java) { AccountLayout.parse(json) }
        }
        for (key in listOf("unexpected", "kind", "name", "tmux_session", "tmux_host", "workspace")) {
            val json = layout.setFavorite(tab.identity, true).json()
            val t = json.getJSONArray("tabs").getJSONObject(0)
            if (key == "unexpected") t.put(key, true) else t.remove(key)
            assertThrows(IllegalArgumentException::class.java) { AccountLayout.parse(json) }
        }
    }

    @Test fun untouchedFavoriteFollowsRemoteIncludingRemoval() {
        for (old in listOf(null, false, true)) {
            for (remoteValue in listOf(null, false, true)) {
                val base = layout.copy(tabs = listOf(tab.copy(favorite = old)))
                val remote = base.copy(revision = 2, tabs = listOf(tab.copy(favorite = remoteValue)))
                val pulled = PendingLayout(base).pull(remote)
                assertEquals(remote, pulled.edited)
                assertFalse(pulled.dirty)
                assertEquals(remoteValue != null, pulled.edited.json().getJSONArray("tabs").getJSONObject(0).has("favorite"))
            }
        }
    }

    @Test fun favoriteEditDuringPutSurvivesAcknowledgementAndRestart() {
        val sent = layout.setFavorite(tab.identity, true)
        val later = sent.setFavorite(tab.identity, false)
        val remote = sent.renameTab(tab.identity, "Laptop rename").copy(revision = 2)
        val pending = PendingLayout.parse(JSONObject(PendingLayout(layout, later).json().toString()))
        val result = pending.acknowledge(sent, remote)
        assertEquals(false, result.edited.tabs[0].favorite)
        assertEquals("Laptop rename", result.edited.tabs[0].name)
        assertTrue(result.dirty)
        assertFalse(result.acknowledge(result.edited, result.edited.copy(revision = 3)).dirty)
    }

    @Test fun favoritesIsFixedAfterAllInEitherOrientationWithoutChangingWorkspaceOrder() {
        val splits = listOf(null, "Other", "Favorites", "Product")
        for (allOnRight in listOf(false, true)) {
            val displayed = inboxSplitIds(splits, allOnRight)
            assertEquals(FAVORITES_SPLIT_ID, displayed[displayed.indexOf(null) + 1])
            assertEquals(1, displayed.count { it == FAVORITES_SPLIT_ID })
            assertEquals(if (allOnRight) splits.asReversed() else splits, displayed.filterNot { it == FAVORITES_SPLIT_ID || it == FREQUENT_SPLIT_ID })
            assertEquals(displayed.size, displayed.distinct().size)
        }
        assertEquals(listOf(FREQUENT_SPLIT_ID, null, FAVORITES_SPLIT_ID), inboxSplitIds(listOf(null), true))
        assertThrows(IllegalArgumentException::class.java) { layout.copy(workspaces = listOf(FAVORITES_SPLIT_ID)).validate() }
    }

    @Test fun everyHttpRequestNegotiatesFavoritesIncludingGetPutAndAuthentication() {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody()).build()
        }.build()
        try {
            val http = AccountHttpClient(client)
            assertEquals(200, http.request("GET", "/v1/layout", "token", null).status)
            http.request("PUT", "/v1/layout", "token", layout.setFavorite(tab.identity, true).json())
            http.request("POST", "/oauth/token", null, JSONObject())
            http.request("POST", "/oauth/revoke", "token", JSONObject())
            assertEquals(listOf("GET", "PUT", "POST", "POST"), requests.map { it.method })
            requests.forEach {
                assertEquals(listOf("favorite,used,visits,input"), it.headers.values("X-Layout-Features"))
                assertEquals("application/json", it.header("Accept"))
                assertEquals("account.multiprompt.dev", it.url.host)
            }
            assertEquals("Bearer token", requests[0].header("Authorization"))
            assertNull(requests[2].header("Authorization"))
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
