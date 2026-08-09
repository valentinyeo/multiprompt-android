package dev.multiprompt.companion.data

import android.content.Context
import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.model.DissolvedSession
import org.json.JSONArray
import org.json.JSONObject

/** Local restore records for sessions dissolved from the remote tmux server. */
class DissolvedSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("dissolved_sessions", Context.MODE_PRIVATE)

    fun load(): List<DissolvedSession> = runCatching {
        val array = JSONArray(preferences.getString(KEY, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    DissolvedSession(
                        hostId = item.getString("hostId"),
                        tmuxSessionName = item.getString("tmuxSessionName"),
                        displayName = item.optString("displayName"),
                        agent = runCatching { AgentKind.valueOf(item.optString("agent")) }
                            .getOrDefault(AgentKind.OTHER),
                        workingDirectory = item.optString("workingDirectory"),
                        resumeCommand = item.optString("resumeCommand"),
                        workspaceId = item.optString("workspaceId").takeIf(String::isNotBlank),
                        workspaceName = item.optString("workspaceName"),
                        dissolvedAtEpochSeconds = item.optLong("dissolvedAtEpochSeconds"),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    fun upsert(session: DissolvedSession) {
        save((load().filterNot { it.key == session.key } + session).takeLast(MAX_RECORDS))
    }

    fun remove(session: DissolvedSession) {
        save(load().filterNot { it.key == session.key })
    }

    fun removeHost(hostId: String) {
        save(load().filterNot { it.hostId == hostId })
    }

    private fun save(sessions: List<DissolvedSession>) {
        val array = JSONArray()
        sessions.forEach { session ->
            array.put(
                JSONObject()
                    .put("hostId", session.hostId)
                    .put("tmuxSessionName", session.tmuxSessionName)
                    .put("displayName", session.displayName)
                    .put("agent", session.agent.name)
                    .put("workingDirectory", session.workingDirectory)
                    .put("resumeCommand", session.resumeCommand)
                    .put("workspaceId", session.workspaceId)
                    .put("workspaceName", session.workspaceName)
                    .put("dissolvedAtEpochSeconds", session.dissolvedAtEpochSeconds),
            )
        }
        preferences.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "records"
        const val MAX_RECORDS = 64
    }
}
