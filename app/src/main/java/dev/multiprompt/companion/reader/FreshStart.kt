package dev.multiprompt.companion.reader

import org.json.JSONObject

object FreshStart {
    const val OP = "fresh_start"
    const val LABEL = "Fresh start"
    const val REQUESTED = "Fresh start requested"
    private val PROVIDERS = setOf("claude", "codex", "pi", "hax", "cursor")
    private val SESSION_ID = Regex("[A-Za-z0-9_-]{1,80}")
    const val NO_ANSWER = "Fresh start failed: the server did not answer"
    private const val BUSY = "Fresh start: wait for this reply to finish"
    private const val UNAVAILABLE = "Fresh start failed: this live chat is unavailable"

    sealed interface Outcome {
        data object Started : Outcome
        data object Already : Outcome
        data class Failed(val message: String) : Outcome
    }

    fun supported(provider: String?, sid: String): Boolean = provider != null && provider in PROVIDERS && SESSION_ID.matches(sid)

    fun query(id: Long, provider: String, sid: String): String {
        require(id > 0 && supported(provider, sid))
        return JSONObject().put("op", OP).put("id", id).put("provider", provider).put("sid", sid).toString() + "\n"
    }

    fun outcome(reply: String, id: Long): Outcome {
        val envelope = runCatching { JSONObject(reply) }.getOrNull() ?: return Outcome.Failed(NO_ANSWER)
        val result = envelope.optJSONObject("result")
        if (envelope.optLong("id", -1) != id || envelope.optInt("message_protocol") != 1 || result == null) {
            return Outcome.Failed(NO_ANSWER)
        }
        return when (result.optString("status")) {
            "started" -> Outcome.Started
            "already" -> Outcome.Already
            "busy" -> Outcome.Failed(BUSY)
            else -> Outcome.Failed(UNAVAILABLE)
        }
    }
}
