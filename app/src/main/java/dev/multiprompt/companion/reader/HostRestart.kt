package dev.multiprompt.companion.reader

import org.json.JSONObject

object HostRestart {
    const val OP = "host_restart"
    const val LABEL = "Restart on newest version"
    const val ALL_LABEL = "Restart all idle chats"
    const val REQUESTED = "Restart requested. A chat that is answering restarts when its reply ends"
    const val NO_ANSWER = "Restart failed: the server did not answer"
    const val NO_CHATS = "No live chat to restart"
    private const val NO_HOST = "Restart: this chat has no live host to restart"
    private const val UNAVAILABLE = "Restart failed: this live chat is unavailable"

    sealed interface Outcome {
        data object Restarted : Outcome
        data object Waiting : Outcome
        data class Failed(val message: String) : Outcome
    }

    data class Counts(val restarted: Int = 0, val waiting: Int = 0, val errors: Int = 0, val unanswered: Int = 0) {
        operator fun plus(other: Counts) = Counts(
            restarted + other.restarted,
            waiting + other.waiting,
            errors + other.errors,
            unanswered + other.unanswered,
        )
    }

    fun supported(provider: String?, sid: String): Boolean = FreshStart.supported(provider, sid)

    fun query(id: Long, provider: String, sid: String): String {
        require(id > 0 && supported(provider, sid))
        return JSONObject().put("op", OP).put("id", id).put("provider", provider).put("sid", sid).toString() + "\n"
    }

    fun queryAll(id: Long): String {
        require(id > 0)
        return JSONObject().put("op", OP).put("id", id).put("all", true).toString() + "\n"
    }

    private fun payload(reply: String, id: Long): JSONObject? {
        val envelope = runCatching { JSONObject(reply) }.getOrNull() ?: return null
        val result = envelope.optJSONObject("result")
        if (envelope.optLong("id", -1) != id || envelope.optInt("message_protocol") != 1) return null
        return result
    }

    fun outcome(reply: String, id: Long): Outcome {
        val result = payload(reply, id) ?: return Outcome.Failed(NO_ANSWER)
        return when (result.optString("status")) {
            "restarted", "restarting" -> Outcome.Restarted
            "waiting" -> Outcome.Waiting
            "already" -> Outcome.Failed(NO_HOST)
            else -> Outcome.Failed(UNAVAILABLE)
        }
    }

    fun counts(reply: String, id: Long): Counts {
        val result = payload(reply, id)
        if (result == null || result.optString("status") != "ok") return Counts(unanswered = 1)
        return Counts(
            restarted = result.optInt("restarted"),
            waiting = result.optInt("waiting"),
            errors = result.optInt("errors"),
        )
    }

    fun summary(counts: Counts): String {
        val parts = buildList {
            if (counts.restarted > 0) add("Restarted ${counts.restarted} idle ${chats(counts.restarted)}")
            if (counts.waiting > 0) add("${counts.waiting} busy ${chats(counts.waiting)} will restart when idle")
            if (counts.errors > 0) add("${counts.errors} ${chats(counts.errors)} could not restart")
            if (counts.unanswered > 0) add("${counts.unanswered} ${if (counts.unanswered == 1) "server" else "servers"} did not answer")
        }
        return if (parts.isEmpty()) "No idle chat to restart" else parts.joinToString(". ")
    }

    private fun chats(count: Int) = if (count == 1) "chat" else "chats"
}
