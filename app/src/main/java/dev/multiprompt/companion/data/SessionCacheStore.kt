package dev.multiprompt.companion.data

import android.content.Context
import dev.multiprompt.companion.model.TmuxSession
import org.json.JSONArray
import org.json.JSONObject

/** Persists the last successful session list so the inbox remains useful offline. */
class SessionCacheStore(context: Context) {
    private val preferences = context.getSharedPreferences("session_cache", Context.MODE_PRIVATE)

    fun load(hostId: String): List<TmuxSession> {
        val raw = preferences.getString(key(hostId), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        TmuxSession(
                            hostId = hostId,
                            name = item.getString("name"),
                            windows = item.optInt("windows"),
                            attachedClients = item.optInt("attachedClients"),
                            lastActivityEpochSeconds = item.optLong("lastActivityEpochSeconds"),
                            columns = item.optInt("columns"),
                            rows = item.optInt("rows"),
                            workingDirectory = item.optString("workingDirectory"),
                            title = item.optString("title"),
                            windowName = item.optString("windowName"),
                            preview = item.optString("preview"),
                            paneCommand = item.optString("paneCommand"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(hostId: String, sessions: List<TmuxSession>) {
        val array = JSONArray()
        sessions.forEach { session ->
            array.put(
                JSONObject()
                    .put("name", session.name)
                    .put("windows", session.windows)
                    .put("attachedClients", session.attachedClients)
                    .put("lastActivityEpochSeconds", session.lastActivityEpochSeconds)
                    .put("columns", session.columns)
                    .put("rows", session.rows)
                    .put("workingDirectory", session.workingDirectory)
                    .put("title", session.title)
                    .put("windowName", session.windowName)
                    .put("preview", session.preview)
                    .put("paneCommand", session.paneCommand),
            )
        }
        preferences.edit().putString(key(hostId), array.toString()).apply()
    }

    fun removeHost(hostId: String) {
        preferences.edit().remove(key(hostId)).apply()
    }

    private fun key(hostId: String): String = "host::$hostId"
}
