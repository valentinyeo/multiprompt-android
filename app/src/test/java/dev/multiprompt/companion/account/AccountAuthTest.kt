package dev.multiprompt.companion.account

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.URI
import java.net.URLDecoder

class AccountAuthTest {
    private val redirect = "dev.multiprompt.companion://auth/callback"
    private val storage = mutableMapOf<String, String>()
    private var time = 10_000L
    private var calls = 0
    private var response = AccountReply(200, tokens())
    private var failure: Exception? = null
    private var lastBody: JSONObject? = null
    private val http = AccountHttp { method, path, _, body ->
        assertEquals("POST", method); assertEquals("/oauth/token", path)
        calls++; lastBody = body
        failure?.let { throw it }
        response
    }
    private fun auth() = AccountAuth(redirect, http, storage::get, { key, value -> if (value == null) storage.remove(key) else storage[key] = value }, { time })
    private fun begin(auth: AccountAuth): Map<String, String> = URI(auth.authorizationUrl("Android test")).rawQuery.split('&').associate {
        val parts = it.split('=', limit = 2)
        parts[0] to URLDecoder.decode(parts[1], "UTF-8")
    }
    private fun login(auth: AccountAuth): Map<String, String> {
        val params = begin(auth)
        assertTrue(auth.callback("$redirect?code=${"c".repeat(43)}&state=${params["state"]}"))
        return params
    }

    @Test fun sameServicePkceAndColdStartCallback() {
        val a = auth()
        val params = begin(a)
        assertEquals(redirect, params["redirect_uri"])
        assertEquals("S256", params["code_challenge_method"])
        val pending = JSONObject(storage.getValue(AccountAuth.PENDING))
        assertEquals(AccountAuth.challenge(pending.getString("verifier")), params["code_challenge"])
        assertEquals(43, params.getValue("state").length)
        assertTrue(auth().callback("$redirect?state=${params["state"]}&code=${"c".repeat(43)}"))
        assertEquals(pending.getString("verifier"), lastBody!!.getString("code_verifier"))
        assertEquals(redirect, lastBody!!.getString("redirect_uri"))
        assertTrue(a.hasSession())
        assertNull(storage[AccountAuth.PENDING])
        assertThrows(IllegalStateException::class.java) { a.callback("$redirect?state=${params["state"]}&code=${"c".repeat(43)}") }
    }

    @Test fun wrongStateUriDuplicateParametersAndExpiredLoginNeverExchange() {
        val a = auth(); val p = begin(a)
        val callback = "$redirect?state=${p["state"]}&code=${"c".repeat(43)}"
        for (uri in listOf(callback.replace(p.getValue("state"), "x".repeat(43)), callback.replace("/callback", "/callback/evil"), "$callback#fragment", "$callback&state=${p["state"]}")) {
            assertThrows(IllegalArgumentException::class.java) { a.callback(uri) }
        }
        time += AccountAuth.LOGIN_LIFETIME_MS + 1
        assertThrows(IllegalArgumentException::class.java) { a.callback(callback) }
        assertEquals(0, calls)
        assertFalse(a.hasSession())
    }

    @Test fun matchingErrorConsumesPendingButWrongStateErrorDoesNot() {
        val a = auth(); val p = begin(a)
        assertThrows(IllegalArgumentException::class.java) { a.callback("$redirect?state=wrong&error=access_denied") }
        assertNotNull(storage[AccountAuth.PENDING])
        assertThrows(IOException::class.java) { a.callback("$redirect?state=${p["state"]}&error=access_denied") }
        assertNull(storage[AccountAuth.PENDING]); assertEquals(0, calls)
    }

    @Test fun cancelledBrowserAndNewLoginReplacePendingStateWithoutLosingExistingTokens() {
        val a = auth(); login(a)
        val old = begin(a); val fresh = begin(a)
        assertNotEquals(old["state"], fresh["state"])
        assertThrows(IllegalArgumentException::class.java) { a.callback("$redirect?state=${old["state"]}&code=${"c".repeat(43)}") }
        assertTrue(a.hasSession()); assertEquals(1, calls)
        a.signOut(); assertFalse(a.hasSession()); assertNull(storage[AccountAuth.PENDING])
    }

    @Test fun tokensAreReusedUntilLastMinuteThenRefreshRotates() {
        val a = auth(); login(a)
        assertEquals("a".repeat(43), a.accessToken()); assertEquals(1, calls)
        time += 539_999
        a.accessToken(); assertEquals(1, calls)
        time++
        response = AccountReply(200, tokens("b", "s"))
        assertEquals("b".repeat(43), a.accessToken()); assertEquals(2, calls)
        assertEquals("refresh_token", lastBody!!.getString("grant_type"))
        assertEquals("r".repeat(43), lastBody!!.getString("refresh_token"))
        assertEquals("s".repeat(43), JSONObject(storage.getValue(AccountAuth.TOKENS)).getString("refresh_token"))
        a.accessToken(); assertEquals(2, calls)
    }

    @Test fun networkTimeout5xx429AndMalformedReplyKeepRefreshCredentials() {
        val a = auth(); login(a); time += 600_000
        val before = storage.getValue(AccountAuth.TOKENS)
        failure = IOException("offline")
        assertThrows(IOException::class.java) { a.accessToken() }
        assertEquals(before, storage[AccountAuth.TOKENS]); assertTrue(a.hasSession())
        failure = null
        for (status in listOf(503, 429, 302, 400, 401)) {
            response = AccountReply(status, JSONObject())
            assertThrows(IOException::class.java) { a.accessToken() }
            assertEquals(before, storage[AccountAuth.TOKENS])
        }
        response = AccountReply(200, tokens().put("refresh_token", "bad"))
        assertThrows(IllegalArgumentException::class.java) { a.accessToken() }
        assertEquals(before, storage[AccountAuth.TOKENS])
        response = AccountReply(200, tokens("b", "s"))
        assertEquals("b".repeat(43), a.accessToken())
    }

    @Test fun explicitInvalidGrantOrRevocationRequiresLogin() {
        for (status in listOf(400, 401)) {
            val a = auth(); response = AccountReply(200, tokens()); login(a); time += 600_000
            response = AccountReply(status, JSONObject().put("error", "invalid_grant"))
            assertThrows(AccountSignInRequired::class.java) { a.accessToken() }
            assertFalse(a.hasSession())
        }
    }

    @Test fun callbackExchangeOutageRetainsPkceAndCodeForRetryAfterRestart() {
        val a = auth(); val p = begin(a); failure = IOException("offline")
        assertThrows(IOException::class.java) { a.callback("$redirect?state=${p["state"]}&code=${"c".repeat(43)}") }
        assertEquals("c".repeat(43), JSONObject(storage.getValue(AccountAuth.PENDING)).getString("code"))
        failure = null
        assertTrue(auth().finishPending()); assertTrue(a.hasSession()); assertNull(storage[AccountAuth.PENDING])
    }

    @Test fun rejectedCodeIsNotRetriedAndExistingLoginSurvivesFailedReplacement() {
        val a = auth(); login(a); val before = storage.getValue(AccountAuth.TOKENS)
        val p = begin(a); response = AccountReply(400, JSONObject().put("error", "invalid_grant"))
        assertThrows(IOException::class.java) { a.callback("$redirect?state=${p["state"]}&code=${"c".repeat(43)}") }
        assertNull(storage[AccountAuth.PENDING]); assertEquals(before, storage[AccountAuth.TOKENS]); assertFalse(a.finishPending())
    }

    companion object {
        private fun tokens(access: String = "a", refresh: String = "r") = JSONObject()
            .put("access_token", access.repeat(43)).put("refresh_token", refresh.repeat(43))
            .put("token_type", "Bearer").put("expires_in", 600).put("device_id", "test-device").put("account_id", "test-account")
    }
}
