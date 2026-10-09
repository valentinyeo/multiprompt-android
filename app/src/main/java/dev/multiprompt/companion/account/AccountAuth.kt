package dev.multiprompt.companion.account

import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class AccountSignInRequired : IOException("Sign in again: this device credential was rejected")

/** Called serially by SyncAuthManager; both credential and pending-login blobs are Keystore protected. */
class AccountAuth(
    val redirect: String,
    private val http: AccountHttp,
    private val read: (String) -> String?,
    private val write: (String, String?) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun hasSession(): Boolean = read(TOKENS) != null
    fun accountId(): String? = read(TOKENS)?.let { JSONObject(it).getString("account_id") }
    fun deviceId(): String? = read(TOKENS)?.let { JSONObject(it).getString("device_id") }

    fun authorizationUrl(deviceName: String): String {
        val verifier = random()
        val state = random()
        write(PENDING, JSONObject().put("verifier", verifier).put("state", state).put("created", clock()).toString())
        val params = mapOf("redirect_uri" to redirect, "state" to state, "code_challenge" to challenge(verifier),
            "code_challenge_method" to "S256", "device_name" to deviceName.take(80))
        return AccountHttpClient.ORIGIN + "/login?" + params.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
    }

    fun callback(uri: String): Boolean {
        val target = URI(uri)
        require(uri.substringBefore('?') == redirect && target.rawFragment == null) { "Unexpected sign-in callback" }
        val fields = target.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
            val parts = it.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }
        require(fields.map { it.first }.distinct().size == fields.size) { "Duplicate sign-in parameter" }
        val params = fields.toMap()
        val pending = read(PENDING)?.let(::JSONObject) ?: error("Sign-in expired; try again")
        require(clock() - pending.getLong("created") in 0..LOGIN_LIFETIME_MS) { "Sign-in expired; try again" }
        require(MessageDigest.isEqual(params["state"].orEmpty().toByteArray(), pending.getString("state").toByteArray())) { "Sign-in state did not match" }
        if (params.containsKey("error")) {
            write(PENDING, null)
            throw IOException("Sign-in was declined; try again")
        }
        val code = params["code"].orEmpty()
        require(Regex("[A-Za-z0-9_-]{43}").matches(code)) { "Invalid sign-in code" }
        require(!pending.has("code") || pending.getString("code") == code) { "Sign-in callback already received" }
        pending.put("code", code)
        write(PENDING, pending.toString())
        return finishPending()
    }

    fun finishPending(): Boolean {
        val pending = read(PENDING)?.let(::JSONObject) ?: return false
        if (!pending.has("code")) return false
        if (clock() - pending.getLong("created") !in 0..LOGIN_LIFETIME_MS) {
            write(PENDING, null)
            throw IOException("Sign-in expired; try again")
        }
        val reply = http.request("POST", "/oauth/token", null, JSONObject().put("grant_type", "authorization_code")
            .put("code", pending.getString("code")).put("code_verifier", pending.getString("verifier")).put("redirect_uri", redirect))
        if (reply.status == 400 && reply.json.optString("error") == "invalid_grant") {
            write(PENDING, null)
            throw IOException("Sign-in code expired or was used; try again")
        }
        saveTokens(reply)
        write(PENDING, null)
        return true
    }

    fun accessToken(): String {
        val stored = read(TOKENS)?.let(::JSONObject) ?: throw AccountSignInRequired()
        if (clock() < stored.getLong("expires_at") - 60_000) return stored.getString("access_token")
        val reply = http.request("POST", "/oauth/token", null, JSONObject().put("grant_type", "refresh_token")
            .put("refresh_token", stored.getString("refresh_token")))
        if (reply.status in listOf(400, 401) && reply.json.optString("error") == "invalid_grant") {
            // Explicit rejection is distinct from an outage; retain cache and pending edits.
            write(TOKENS, null)
            throw AccountSignInRequired()
        }
        saveTokens(reply)
        return reply.json.getString("access_token")
    }

    fun invalidateAccess() {
        read(TOKENS)?.let { write(TOKENS, JSONObject(it).put("expires_at", 0).toString()) }
    }

    fun signOut() {
        write(TOKENS, null)
        write(PENDING, null)
    }

    private fun saveTokens(reply: AccountReply) {
        if (reply.status != 200) throw IOException("Account service unavailable (${reply.status}); retry when online")
        val json = reply.json
        require(listOf("access_token", "refresh_token").all { Regex("[A-Za-z0-9_-]{43}").matches(json.getString(it)) }) { "Invalid account token response" }
        require(json.getString("token_type") == "Bearer" && json.getLong("expires_in") in 1..3600 &&
            json.getString("account_id").isNotEmpty() && json.getString("device_id").isNotEmpty()) { "Invalid account token response" }
        write(TOKENS, JSONObject(json.toString()).put("expires_at", clock() + json.getLong("expires_in") * 1000).toString())
    }

    companion object {
        const val TOKENS = "account_device_v1"
        const val PENDING = "account_login_v1"
        const val LOGIN_LIFETIME_MS = 10 * 60_000L
        fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        )
        private fun random(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    }
}
