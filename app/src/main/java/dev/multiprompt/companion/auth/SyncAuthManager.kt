package dev.multiprompt.companion.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.browser.customtabs.CustomTabsIntent
import dev.multiprompt.companion.BuildConfig
import dev.multiprompt.companion.account.AccountAuth
import dev.multiprompt.companion.account.AccountHttp
import dev.multiprompt.companion.account.AccountHttpClient
import dev.multiprompt.companion.account.AccountReply
import dev.multiprompt.companion.account.CrashReportUpload
import dev.multiprompt.companion.data.CrashReport
import dev.multiprompt.companion.security.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

class SyncAuthManager(context: Context, private val http: AccountHttp = AccountHttpClient()) {
    private val secrets = SecretStore(context.applicationContext)
    private val mutex = Mutex()
    private val auth = AccountAuth(
        "${BuildConfig.APPLICATION_ID}://auth/callback", http,
        { id -> secrets.get(id)?.toString(Charsets.UTF_8) },
        { id, value -> if (value == null) secrets.remove(id) else secrets.put(id, value.toByteArray()) },
    )

    init {
        context.getSharedPreferences("sync_auth", Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun hasSession(): Boolean = auth.hasSession()
    fun accountId(): String? = auth.accountId()

    suspend fun buildAuthorizationIntent(): Intent = withContext(Dispatchers.IO) {
        mutex.withLock {
            CustomTabsIntent.Builder().build().intent.apply {
                data = Uri.parse(auth.authorizationUrl("Android ${Build.MANUFACTURER} ${Build.MODEL}"))
            }
        }
    }

    suspend fun handleAuthorizationResponse(data: Intent?): TokenResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                auth.callback(data?.data?.toString() ?: return@withLock TokenResult.Failure("Sign-in was cancelled"))
                TokenResult.Success
            } catch (e: Exception) { TokenResult.Failure(e.message ?: "Sign-in failed; try again") }
        }
    }

    suspend fun finishPending(): Boolean = withContext(Dispatchers.IO) { mutex.withLock { auth.finishPending() } }

    suspend fun request(method: String, path: String, body: JSONObject? = null): AccountReply = withContext(Dispatchers.IO) {
        mutex.withLock {
            var reply = http.request(method, path, auth.accessToken(), body)
            if (reply.status == 401) {
                auth.invalidateAccess()
                reply = http.request(method, path, auth.accessToken(), body)
            }
            if (reply.status !in listOf(200, 409)) throw IOException("Account request failed (${reply.status}); retry when online")
            reply
        }
    }

    suspend fun uploadCrashReport(report: CrashReport, markUploaded: (String) -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock { CrashReportUpload(http, auth).upload(report, markUploaded) }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val stored = secrets.get(AccountAuth.TOKENS)?.toString(Charsets.UTF_8)?.let(::JSONObject)
            val device = stored?.optString("device_id")
            val token = stored?.optString("access_token")
            auth.signOut()
            if (device != null && token != null) runCatching { http.request("DELETE", "/v1/devices/$device", token, null) }
        }
    }

    sealed interface TokenResult {
        data object Success : TokenResult
        data class Failure(val message: String) : TokenResult
    }
}
