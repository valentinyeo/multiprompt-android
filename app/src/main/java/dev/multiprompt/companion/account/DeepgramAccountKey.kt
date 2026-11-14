package dev.multiprompt.companion.account

import dev.multiprompt.companion.dictation.DeepgramKey

class DeepgramAccountKey(private val http: AccountHttp, private val auth: AccountAuth) {
    fun fetch(): String? {
        if (!auth.hasSession()) return null
        var reply = http.request("GET", PATH, auth.accessToken(clearRejectedSession = false), null)
        if (reply.status == 401) {
            auth.invalidateAccess()
            reply = http.request("GET", PATH, auth.accessToken(clearRejectedSession = false), null)
        }
        if (reply.status != 200) return null
        val raw = reply.json.optString("key", "")
        if (raw.length > MAX_CHARACTERS || !SERVICE_KEY.matches(raw)) return null
        return DeepgramKey.normalize(raw)
    }

    companion object {
        const val PATH = "/v1/secrets/deepgram"
        private const val MAX_CHARACTERS = 256
        private val SERVICE_KEY = Regex("[!-~]+")
    }
}
