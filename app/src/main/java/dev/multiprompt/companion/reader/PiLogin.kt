package dev.multiprompt.companion.reader

import org.json.JSONObject

object PiLogin {
    const val OP = "login"
    private val PROVIDER_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
    private val SESSION_ID = Regex("[A-Za-z0-9_-]{1,80}")
    private const val BUSY = "Pi login: wait for this reply to finish"
    private const val UNAVAILABLE = "Pi login failed: this live chat is unavailable"
    const val NO_ANSWER = "Pi login failed: the server did not answer"

    fun supported(provider: String?, sid: String): Boolean = provider == "pi" && SESSION_ID.matches(sid)

    fun loginLine(id: Long, sid: String, providerId: String?): String {
        require(id > 0 && supported("pi", sid))
        val query = JSONObject().put("id", id).put("op", OP).put("provider", "pi").put("sid", sid)
        providerId?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            require(PROVIDER_ID.matches(raw))
            query.put("provider_id", raw)
        }
        return query.toString()
    }

    fun parseLoginSlash(text: String): String? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/login")) return null
        val rest = trimmed.removePrefix("/login").trim()
        if (rest.isEmpty()) return null
        val providerId = rest.lineSequence().first().trim().split(Regex("\\s+"), 2).first()
        return providerId.takeIf { PROVIDER_ID.matches(it) }
    }

    fun loginBusy(result: JSONObject): Boolean = result.optString("status") == "busy" && result.optString("reason") == "login_busy"

    fun outcome(reply: String, id: Long): MessageActionResult {
        val envelope = runCatching { JSONObject(reply) }.getOrNull() ?: return MessageActionResult.Rejected(NO_ANSWER)
        val result = envelope.optJSONObject("result")
        if (envelope.optLong("id", -1) != id || envelope.optInt("message_protocol") != 1 || result == null) {
            return MessageActionResult.Rejected(NO_ANSWER)
        }
        return when (result.optString("status")) {
            "ok" -> MessageActionResult.Applied
            "busy" -> MessageActionResult.Rejected(if (loginBusy(result)) BUSY else UNAVAILABLE)
            "refused", "error" -> MessageActionResult.Rejected(UNAVAILABLE)
            else -> MessageActionResult.Rejected(NO_ANSWER)
        }
    }
}