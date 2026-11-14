package dev.multiprompt.companion.data

import android.content.Context
import android.content.SharedPreferences
import dev.multiprompt.companion.model.TmuxSession
import org.json.JSONArray
import org.json.JSONObject

internal interface SessionCacheBacking {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun remove(key: String)
}

private class PreferencesBacking(private val preferences: SharedPreferences) : SessionCacheBacking {
    override fun read(key: String): String? = preferences.getString(key, null)

    override fun write(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }
}

internal object SessionCacheCodec {
    fun decode(hostId: String, raw: String): List<TmuxSession> = runCatching {
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
                        hostSessionId = item.optString("hostSessionId"),
                        outputActivityEpochSeconds = item.optLong("outputActivityEpochSeconds"),
                        replyAtEpochSeconds = if (item.has("replyAtEpochSeconds")) item.optLong("replyAtEpochSeconds") else null,
                        replyWaiting = if (item.has("replyWaiting")) item.optBoolean("replyWaiting") else null,
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    fun encode(sessions: List<TmuxSession>): String {
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
                    .put("paneCommand", session.paneCommand)
                    .put("hostSessionId", session.hostSessionId)
                    .put("outputActivityEpochSeconds", session.outputActivityEpochSeconds)
                    .put("replyAtEpochSeconds", session.replyAtEpochSeconds)
                    .put("replyWaiting", session.replyWaiting),
            )
        }
        return array.toString()
    }

    fun persisted(hostId: String, session: TmuxSession): TmuxSession = session.copy(
        hostId = hostId,
        accountName = null,
        workspaceName = "",
        workspaceColor = 0,
    )
}

class SessionCacheStore internal constructor(
    private val backing: SessionCacheBacking,
    private val decode: (String, String) -> List<TmuxSession> = SessionCacheCodec::decode,
) {
    constructor(context: Context) : this(
        PreferencesBacking(context.getSharedPreferences("session_cache", Context.MODE_PRIVATE)),
    )

    private class Entry(val raw: String?, val sessions: List<TmuxSession>)

    private val lock = Any()
    private val entries = HashMap<String, Entry>()

    fun load(hostId: String): List<TmuxSession> = synchronized(lock) {
        entries.getOrPut(hostId) {
            val raw = backing.read(key(hostId))
            Entry(raw, if (raw == null) emptyList() else decode(hostId, raw))
        }.sessions
    }

    fun save(hostId: String, sessions: List<TmuxSession>) {
        val encoded = SessionCacheCodec.encode(sessions)
        synchronized(lock) {
            val known = entries[hostId]?.raw ?: backing.read(key(hostId))
            if (known == encoded) return
            backing.write(key(hostId), encoded)
            entries[hostId] = Entry(encoded, sessions.map { SessionCacheCodec.persisted(hostId, it) })
        }
    }

    fun removeHost(hostId: String) {
        synchronized(lock) {
            backing.remove(key(hostId))
            entries[hostId] = Entry(null, emptyList())
        }
    }

    private fun key(hostId: String): String = "host::$hostId"
}
