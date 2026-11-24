package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.TmuxSession
import dev.multiprompt.companion.ssh.TmuxText
import org.json.JSONObject

object RestartNewest {
    const val OP = "host_restart"
    const val CHAT_LABEL = "Restart on newest version"
    const val INBOX_LABEL = "Restart all idle chats"
    const val NO_ANSWER = "Chat restart: the server did not answer"
    const val NOT_HOSTED = "Chat restart: this chat has no live host to restart"
    private val PROVIDERS = setOf("claude", "codex", "pi", "hax", "cursor")
    private val SESSION_ID = Regex("[A-Za-z0-9_-]{1,80}")
    private val TMUX_NAME = Regex("[A-Za-z0-9_.:-]{1,128}")

    sealed interface Outcome {
        data object Restarted : Outcome
        data object Restarting : Outcome
        data object Waiting : Outcome
        data class Failed(val message: String) : Outcome
    }

    data class Target(
        val hostId: String,
        val provider: String,
        val sid: String,
        val tmux: String,
    )

    data class BatchResult(
        val restarted: Int = 0,
        val restarting: Int = 0,
        val waiting: Int = 0,
        val failed: Int = 0,
    ) {
        val started: Int get() = restarted + restarting
    }

    fun sessionKey(hostId: String, name: String): String = "$hostId::$name"

    fun supported(provider: String?, sid: String): Boolean =
        provider != null && provider in PROVIDERS && SESSION_ID.matches(sid)

    fun query(id: Long, provider: String, sid: String, tmux: String = ""): String {
        require(id > 0 && supported(provider, sid))
        if (tmux.isNotEmpty()) require(TMUX_NAME.matches(tmux))
        val payload = JSONObject().put("op", OP).put("id", id).put("provider", provider).put("sid", sid)
        if (tmux.isNotEmpty()) payload.put("tmux", tmux)
        return payload.toString() + "\n"
    }

    fun outcome(reply: String, id: Long): Outcome {
        val envelope = runCatching { JSONObject(reply) }.getOrNull() ?: return Outcome.Failed(NO_ANSWER)
        val result = envelope.optJSONObject("result")
        if (envelope.optLong("id", -1) != id || envelope.optInt("message_protocol") != 1 || result == null) {
            return Outcome.Failed(NO_ANSWER)
        }
        return when (result.optString("status")) {
            "restarted" -> Outcome.Restarted
            "restarting" -> Outcome.Restarting
            "waiting" -> Outcome.Waiting
            "already", "refused", "error" -> Outcome.Failed(NOT_HOSTED)
            else -> Outcome.Failed(NO_ANSWER)
        }
    }

    fun notice(outcome: Outcome): String = when (outcome) {
        Outcome.Restarted -> "Chat restarted on the newest version"
        Outcome.Restarting -> "Chat is restarting on the newest version"
        Outcome.Waiting -> "Chat restarts when this reply ends"
        is Outcome.Failed -> outcome.message
    }

    fun batchNotice(result: BatchResult): String = when {
        result.started > 0 && result.failed == 0 ->
            if (result.started == 1) "Restarted 1 idle chat on the newest version"
            else "Restarted ${result.started} idle chats on the newest version"
        result.started > 0 ->
            "Restarted ${result.started} idle ${if (result.started == 1) "chat" else "chats"}; ${result.failed} failed"
        result.waiting > 0 -> "Idle chats will restart when their replies end"
        result.failed > 0 -> NO_ANSWER
        else -> "No idle chats to restart"
    }

    fun isIdle(session: TmuxSession, workingKeys: Set<String> = emptySet()): Boolean {
        if (sessionKey(session.hostId, session.name) in workingKeys) return false
        return session.replyWaiting ?: TmuxText.isWaitingForInput(session.preview, session.agent)
    }

    fun target(session: TmuxSession): Target? {
        val provider = ChatHistoryProtocol.hostedProvider(session.agent, session.hostSessionId) ?: return null
        if (!supported(provider, session.hostSessionId) || !TMUX_NAME.matches(session.name)) return null
        return Target(session.hostId, provider, session.hostSessionId, session.name)
    }

    fun selectIdle(sessions: List<TmuxSession>, workingKeys: Set<String> = emptySet()): List<Target> {
        val seen = LinkedHashSet<String>()
        val selected = mutableListOf<Target>()
        for (session in sessions) {
            if (!isIdle(session, workingKeys)) continue
            val item = target(session) ?: continue
            val identity = "${item.hostId}\t${item.provider}\t${item.sid}"
            if (seen.add(identity)) selected += item
        }
        return selected
    }
}
