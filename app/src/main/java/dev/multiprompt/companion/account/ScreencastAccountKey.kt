package dev.multiprompt.companion.account

class ScreencastAccountKey(private val http: AccountHttp, private val auth: AccountAuth) {
    fun fetch(): String? {
        if (!auth.hasSession()) return null
        var reply = http.request("GET", PATH, auth.accessToken(clearRejectedSession = false), null)
        if (reply.status == 401) {
            auth.invalidateAccess()
            reply = http.request("GET", PATH, auth.accessToken(clearRejectedSession = false), null)
        }
        if (reply.status != 200) return null
        val raw = reply.json.opt("key") as? String ?: return null
        return raw.takeIf { it.length <= MAX_CHARACTERS && SERVICE_KEY.matches(it) }
    }

    companion object {
        const val PATH = "/v1/secrets/screencast2"
        private const val MAX_CHARACTERS = 256
        private val SERVICE_KEY = Regex("[!-~]+")
    }
}
