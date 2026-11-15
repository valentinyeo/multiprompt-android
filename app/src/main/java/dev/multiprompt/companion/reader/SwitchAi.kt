package dev.multiprompt.companion.reader

import org.json.JSONObject

object SwitchAi {
    const val OP = "switch_ai"
    const val LABEL = "Switch AI"
    const val NO_ANSWER = "Switch AI failed: the server did not answer"
    const val UNKNOWN = "Switch AI: no answer yet; the chat will update if it went through"
    private const val BUSY = "Switch AI: wait for this reply to finish"
    private const val UNAVAILABLE = "Switch AI is not available for this chat"
    private const val FAILED = "Switch AI failed: the chat could not be moved"
    private val SESSION_ID = Regex("[A-Za-z0-9_-]{1,80}")

    data class Target(val provider: String, val label: String)

    val TARGETS = listOf(Target("claude", "Claude"), Target("codex", "Codex"), Target("pi", "Pi"))

    sealed interface Outcome {
        data class Switched(val provider: String, val sid: String) : Outcome
        data object Unknown : Outcome
        data class Failed(val message: String) : Outcome
    }

    fun supported(provider: String?, sid: String): Boolean =
        provider != null && TARGETS.any { it.provider == provider } && SESSION_ID.matches(sid)

    fun targets(provider: String?): List<Target> = TARGETS.filter { it.provider != provider }

    fun label(provider: String): String = TARGETS.firstOrNull { it.provider == provider }?.label ?: provider

    fun query(id: Long, provider: String, sid: String, to: String): String {
        require(id > 0 && supported(provider, sid) && to != provider && TARGETS.any { it.provider == to })
        return JSONObject().put("op", OP).put("id", id).put("provider", provider).put("sid", sid).put("to", to).toString() + "\n"
    }

    fun outcome(reply: String, id: Long, to: String): Outcome {
        val envelope = runCatching { JSONObject(reply) }.getOrNull() ?: return Outcome.Failed(NO_ANSWER)
        val result = envelope.optJSONObject("result")
        if (envelope.optLong("id", -1) != id || envelope.optInt("message_protocol") != 1 || result == null) {
            return Outcome.Failed(NO_ANSWER)
        }
        return when (result.optString("status")) {
            "switched" -> {
                val provider = result.optString("provider")
                val sid = result.optString("sid")
                if (provider == to && SESSION_ID.matches(sid)) Outcome.Switched(provider, sid) else Outcome.Unknown
            }
            "busy" -> Outcome.Failed(BUSY)
            "refused" -> Outcome.Failed(UNAVAILABLE)
            "unavailable" -> Outcome.Failed(UNAVAILABLE)
            else -> Outcome.Failed(FAILED)
        }
    }
}
