package dev.multiprompt.companion.account

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DeepgramAccountKeyTest {
    private val storage = mutableMapOf<String, String>()
    private val requests = mutableListOf<Triple<String, String, String?>>()
    private val goodKey = (0 until 40).joinToString("") { (it % 16).toString(16) }
    private var reply = AccountReply(200, JSONObject().put("key", goodKey))
    private var refreshReply = AccountReply(200, tokens("b"))
    private var failure: IOException? = null
    private val http = AccountHttp { method, path, token, json ->
        requests += Triple(method, path, token)
        failure?.let { throw it }
        if (path == "/oauth/token") {
            assertNull(token)
            assertEquals("refresh_token", json!!.getString("grant_type"))
            refreshReply
        } else {
            assertNull(json)
            reply
        }
    }
    private val auth = AccountAuth("dev.multiprompt.companion://auth/callback", http, storage::get,
        { key, value -> if (value == null) storage.remove(key) else storage[key] = value }, { 10_000L })
    private val client = DeepgramAccountKey(http, auth)

    private fun signIn() { storage[AccountAuth.TOKENS] = tokens("a").put("expires_at", 610_000L).toString() }

    @Test fun signedOutNeverCallsTheAccount() {
        assertNull(client.fetch())
        assertTrue(requests.isEmpty())
    }

    @Test fun signedInGetsTheKeyWithTheDeviceToken() {
        signIn()
        assertEquals(goodKey, client.fetch())
        assertEquals(listOf(Triple("GET", "/v1/secrets/deepgram", "a".repeat(43))), requests)
    }

    @Test fun noStoredKeyAnswersNothing() {
        signIn(); reply = AccountReply(404, JSONObject().put("error", "not_found"))
        assertNull(client.fetch())
        assertTrue(auth.hasSession())
    }

    @Test fun serverErrorsAndForbiddenAnswerNothingAndKeepLogin() {
        signIn()
        for (status in listOf(403, 429, 500, 503)) {
            reply = AccountReply(status, JSONObject().put("key", goodKey))
            assertNull(client.fetch())
            assertTrue(auth.hasSession())
        }
    }

    @Test fun networkFailureKeepsLogin() {
        signIn(); failure = IOException("offline")
        try { client.fetch(); fail("expected IOException") } catch (_: IOException) { }
        assertTrue(auth.hasSession())
        assertEquals("r".repeat(43), JSONObject(storage.getValue(AccountAuth.TOKENS)).getString("refresh_token"))
    }

    @Test fun rejectedRefreshNeverClearsLogin() {
        for (status in listOf(400, 401, 503)) {
            signIn(); requests.clear()
            reply = AccountReply(401, JSONObject())
            refreshReply = AccountReply(status, JSONObject().put("error", "invalid_grant"))
            try { client.fetch() } catch (_: IOException) { }
            assertTrue(auth.hasSession())
            assertEquals("r".repeat(43), JSONObject(storage.getValue(AccountAuth.TOKENS)).getString("refresh_token"))
        }
    }

    @Test fun unauthorizedRefreshesOnceThenRetriesOnce() {
        signIn()
        var gets = 0
        val retryHttp = AccountHttp { method, path, token, json ->
            if (path == "/oauth/token") http.request(method, path, token, json)
            else {
                requests += Triple(method, path, token)
                if (gets++ == 0) AccountReply(401, JSONObject()) else AccountReply(200, JSONObject().put("key", goodKey))
            }
        }
        assertEquals(goodKey, DeepgramAccountKey(retryHttp, auth).fetch())
        assertEquals(listOf(Triple("GET", "/v1/secrets/deepgram", "a".repeat(43)), Triple("POST", "/oauth/token", null),
            Triple("GET", "/v1/secrets/deepgram", "b".repeat(43))).map { it.second to it.third }, requests.map { it.second to it.third })
        assertTrue(auth.hasSession())
    }

    @Test fun malformedKeysAreRefused() {
        signIn()
        for (bad in listOf("", "short", "has space ${"x".repeat(40)}", "x".repeat(257), "kéy${"x".repeat(40)}")) {
            reply = AccountReply(200, JSONObject().put("key", bad))
            assertNull(bad, client.fetch())
        }
        reply = AccountReply(200, JSONObject())
        assertNull(client.fetch())
    }

    private companion object {
        fun tokens(access: String) = JSONObject().put("access_token", access.repeat(43))
            .put("refresh_token", "r".repeat(43)).put("token_type", "Bearer").put("expires_in", 600)
            .put("device_id", "test-device").put("account_id", "test-account")
    }
}
