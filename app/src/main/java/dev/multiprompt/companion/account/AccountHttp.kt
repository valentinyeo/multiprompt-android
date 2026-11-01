package dev.multiprompt.companion.account

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

fun interface AccountHttp {
    fun request(method: String, path: String, token: String?, body: JSONObject?): AccountReply
}

data class AccountReply(val status: Int, val json: JSONObject, val layoutFeatures: String? = null)

class AccountHttpClient(
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).build(),
) : AccountHttp {

    override fun request(method: String, path: String, token: String?, body: JSONObject?): AccountReply {
        val request = Request.Builder().url(ORIGIN + path).header("Accept", "application/json")
            .header("X-Layout-Features", "favorite,muted,used,visits,input,settings,hosts,workspace_color,bot_appearance")
            .method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
        token?.let { request.header("Authorization", "Bearer $it") }
        client.newCall(request.build()).execute().use { reply ->
            val source = reply.body.source()
            if (source.request(1024L * 1024L + 1)) throw IOException("Account response too large")
            val text = source.readUtf8()
            val json = runCatching { JSONObject(text) }.getOrElse {
                if (reply.isSuccessful) throw IOException("Invalid account response") else JSONObject()
            }
            return AccountReply(reply.code, json, reply.header("X-Layout-Features"))
        }
    }

    companion object { val ORIGIN: String = dev.multiprompt.companion.BuildConfig.ACCOUNT_ORIGIN }
}
