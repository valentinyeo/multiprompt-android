package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import org.json.JSONObject

object AiStopped {
    const val OP = "restart_ai"
    const val RESTART_GRACE_MILLIS = 45_000L
    const val NOTICE = "AI stopped"
    private val SHELLS = setOf("bash", "zsh", "sh", "dash", "fish", "ksh")
    private val SESSION_ID = Regex("[A-Za-z0-9-]{8,64}")
    private const val MANUAL = "Could not restart the AI. Open the terminal and start it by hand"

    sealed interface Outcome {
        data object Restarted : Outcome
        data object AlreadyRunning : Outcome
        data class Failed(val message: String) : Outcome
    }

    fun paneCommandIsShell(command: String): Boolean =
        command.trim().substringAfterLast('/').trimStart('-').lowercase() in SHELLS

    fun stopped(agent: AgentKind, paneCommand: String, restartRequestedAtMillis: Long, nowMillis: Long): Boolean =
        agent == AgentKind.CLAUDE && paneCommandIsShell(paneCommand) &&
            (restartRequestedAtMillis <= 0 || nowMillis - restartRequestedAtMillis >= RESTART_GRACE_MILLIS)

    fun restartSessionId(sid: String): String = sid.takeIf { SESSION_ID.matches(it) }.orEmpty()

    fun query(id: Long, tmux: String, sid: String, cwd: String): String {
        require(id > 0)
        return JSONObject().put("op", OP).put("id", id).put("tmux", tmux)
            .put("sid", restartSessionId(sid)).put("cwd", cwd).toString() + "\n"
    }

    fun outcome(wire: String, id: Long): Outcome {
        val fields = wire.lineSequence().firstOrNull()?.split('\t').orEmpty()
        if (fields.size < 5 || fields[0] != "@mp" || fields[1] != id.toString() || fields[2] != OP) {
            return Outcome.Failed(MANUAL)
        }
        return when {
            fields[3] == "ok" -> Outcome.Restarted
            fields[4] == "pane_busy" -> Outcome.AlreadyRunning
            fields[4] == "invalid_session" -> Outcome.Failed("This chat has no valid saved session. Open the terminal and start the AI by hand")
            else -> Outcome.Failed(MANUAL)
        }
    }
}
