package dev.multiprompt.companion.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * multiprompt account login — Cloudflare Access for SaaS (OIDC), email OTP as the login
 * method. The flow: AppAuth opens the browser (Custom Tabs), the user types the emailed
 * code, Access redirects to the app deep link with an authorization code, AppAuth
 * exchanges it (PKCE) for ID/access/refresh tokens. Refresh tokens rotate; revoking the
 * Access session kills the next refresh. No service tokens, no embedded secrets — the
 * client runs as a public PKCE client.
 *
 * See docs/sync-protocol-v1.md, "M0 spike result" for the verified endpoints.
 */
class SyncAuthManager(context: Context) {

    private val appContext = context.applicationContext
    private val authService = AuthorizationService(appContext)

    /** Zero Trust team domain + this app's OIDC client id (IGSH-223). */
    private val teamDomain = "https://vwo-analyzer.cloudflareaccess.com"
    private val clientId = CLIENT_ID
    private val redirectUri = "dev.multiprompt.companion://auth/callback"
    private val scopes = "openid email profile offline_access"

    /** The discovered endpoints (per-app discovery served by Access). */
    val discovery: Discovery by lazy {
        Discovery(
            authorizationEndpoint = "$teamDomain/cdn-cgi/access/sso/oidc/$clientId/authorization",
            tokenEndpoint = "$teamDomain/cdn-cgi/access/sso/oidc/$clientId/token",
        )
    }

    data class Discovery(val authorizationEndpoint: String, val tokenEndpoint: String)

    /** True when a refresh token exists on this device. */
    fun hasSession(): Boolean = refreshSecretId()?.isNotBlank() == true

    fun buildAuthorizationIntent(): android.content.Intent {
        val codeVerifier = newCodeVerifier()
        val codeChallenge = codeVerifierSha256Base64Url(codeVerifier)
        preferences.edit()
            .putString(KEY_CODE_VERIFIER, codeVerifier)
            .apply()

        val config = net.openid.appauth.AuthorizationServiceConfiguration(
            android.net.Uri.parse(discovery.authorizationEndpoint),
            android.net.Uri.parse(discovery.tokenEndpoint),
        )
        val request = AuthorizationRequest.Builder(
            config,
            clientId,
            ResponseTypeValues.CODE,
            Uri.parse(redirectUri),
        )
            .setScope(scopes)
            .setCodeVerifier(codeVerifier, codeChallenge, "S256")
            .build()
        return authService.getAuthorizationRequestIntent(request)
    }

    /** Exchanges the deep-link data for tokens and persists the refresh token. */
    suspend fun handleAuthorizationResponse(data: android.content.Intent?): TokenResult {
        val codeExchange = AuthorizationResponse.fromIntent(data)
            ?: return TokenResult.Failure("login was cancelled or the redirect did not match")
        val verifier = preferences.getString(KEY_CODE_VERIFIER, null)
            ?: return TokenResult.Failure("login session expired — try again")
        val baseRequest = codeExchange.createTokenExchangeRequest()
        val tokenRequest = TokenRequest.Builder(
            AuthorizationServiceConfiguration(
                android.net.Uri.parse(discovery.authorizationEndpoint),
                android.net.Uri.parse(discovery.tokenEndpoint),
            ),
            clientId,
        )
            .setGrantType(net.openid.appauth.GrantTypeValues.AUTHORIZATION_CODE)
            .setAuthorizationCode(codeExchange.authorizationCode)
            .setRedirectUri(redirectUri)
            .setCodeVerifier(verifier)
            .build()

        val tokens = exchangeTokenRequest(tokenRequest)
            ?: return TokenResult.Failure("token exchange failed — check the network and try again")
        preferences.edit()
            .putString(KEY_CURRENT_REFRESH, tokens.refreshToken ?: "")
            .putString(KEY_ACCESS_CACHE, tokens.accessToken ?: "")
            .putLong(KEY_ACCESS_EXPIRES_AT, System.currentTimeMillis() + (tokens.expiresIn * 1000L) - EXPIRY_SLACK_MS)
            .remove(KEY_CODE_VERIFIER)
            .apply()
        return TokenResult.Success(
            accessToken = tokens.accessToken ?: return TokenResult.Failure("token response had no access_token"),
            idToken = tokens.idToken ?: "",
            expiresInSeconds = tokens.expiresIn,
        )
    }

    sealed class TokenResult {
        data class Success(
            val accessToken: String,
            val idToken: String?,
            val expiresInSeconds: Long,
        ) : TokenResult()

        data class Failure(val message: String) : TokenResult()
    }

    /**
     * Returns a valid access token, refreshing via the stored refresh token when needed.
     * Null means the user must log in again (no refresh token, or refresh rejected —
     * e.g. the Access session was revoked; the stored token is then cleared).
     */
    suspend fun validAccessToken(): String? {
        val expiresAt = preferences.getLong(KEY_ACCESS_EXPIRES_AT, 0)
        val cached = preferences.getString(KEY_ACCESS_CACHE, null)
        if (cached != null && System.currentTimeMillis() < expiresAt) return cached

        val refreshToken = preferences.getString(KEY_CURRENT_REFRESH, null) ?: return null
        if (refreshToken.isBlank()) return null

        val request = TokenRequest.Builder(
            AuthorizationServiceConfiguration(
                android.net.Uri.parse(discovery.authorizationEndpoint),
                android.net.Uri.parse(discovery.tokenEndpoint),
            ),
            clientId,
        )
            .setGrantType(net.openid.appauth.GrantTypeValues.REFRESH_TOKEN)
            .setRefreshToken(refreshToken)
            .build()
        val tokens = exchangeTokenRequest(request) ?: run {
            signOut()
            return null
        }
        preferences.edit()
            .putString(KEY_CURRENT_REFRESH, tokens.refreshToken ?: refreshToken)
            .putString(KEY_ACCESS_CACHE, tokens.accessToken ?: "")
            .putLong(KEY_ACCESS_EXPIRES_AT, System.currentTimeMillis() + (tokens.expiresIn * 1000L) - EXPIRY_SLACK_MS)
            .apply()
        return tokens.accessToken
    }

    /** Kills the stored tokens on this device (the Access session itself is revoked server-side). */
    fun signOut() {
        preferences.edit()
            .remove(KEY_CURRENT_REFRESH)
            .remove(KEY_ACCESS_CACHE)
            .remove(KEY_ACCESS_EXPIRES_AT)
            .remove(KEY_CODE_VERIFIER)
            .apply()
    }

    // --- token exchange over plain HTTPS (AppAuth's native exchange needs a UI loop) -----

    private data class Tokens(
        val accessToken: String?,
        val idToken: String?,
        val refreshToken: String?,
        val expiresIn: Long,
    )

    private fun exchangeTokenRequest(request: TokenRequest): Tokens? = runCatching {
        val form = buildString {
            append("grant_type=").append(java.net.URLEncoder.encode(request.grantType, "UTF-8"))
            append("&client_id=").append(java.net.URLEncoder.encode(request.clientId, "UTF-8"))
            request.authorizationCode?.let {
                append("&code=").append(java.net.URLEncoder.encode(it, "UTF-8"))
                append("&redirect_uri=").append(java.net.URLEncoder.encode(request.redirectUri.toString(), "UTF-8"))
            }
            request.refreshToken?.let {
                append("&refresh_token=").append(java.net.URLEncoder.encode(it, "UTF-8"))
            }
            request.codeVerifier?.let {
                append("&code_verifier=").append(java.net.URLEncoder.encode(it, "UTF-8"))
            }
        }
        val connection = URL(discovery.tokenEndpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.setRequestProperty("Accept", "application/json")
        connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        if (connection.responseCode !in 200..299) return null
        val json = JSONObject(body)
        Tokens(
            accessToken = json.optString("access_token").ifEmpty { null },
            idToken = json.optString("id_token").ifBlank { null },
            refreshToken = json.optString("refresh_token").ifBlank { null },
            expiresIn = json.optLong("expires_in", 300),
        )
    }.getOrNull()

    private fun subjectFromJwt(jwt: String): String? = runCatching {
        val payload = jwt.substringBefore('.').let { jwt.split('.')[1] }
        val decoded = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        JSONObject(decoded).optString("sub").ifBlank { null }
    }.getOrNull()

    private fun refreshSecretId(): String? = preferences.getString(KEY_CURRENT_REFRESH, null)

    private val preferences = appContext.getSharedPreferences("sync_auth", Context.MODE_PRIVATE)

    private fun newCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun codeVerifierSha256Base64Url(verifier: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        private const val KEY_CODE_VERIFIER = "code_verifier"
        private const val KEY_CURRENT_REFRESH = "current_refresh_token"
        private const val KEY_ACCESS_CACHE = "access_token_cache"
        private const val KEY_ACCESS_EXPIRES_AT = "access_token_expires_at"
            private const val EXPIRY_SLACK_MS = 30_000L

        /** Public PKCE client registered in the Zero Trust SaaS application (IGSH-223). */
        const val CLIENT_ID = "2394ba974f9c1921e1702c527ef91179e2f60d815a4008ec92bc00b3bd17e730"
    }
}
