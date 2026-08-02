package dev.multiprompt.companion.data

import android.content.Context
import dev.multiprompt.companion.model.TmuxSession

class SessionReadStore(context: Context) {
    private val preferences = context.getSharedPreferences("session_read_state", Context.MODE_PRIVATE)

    fun isUnread(session: TmuxSession): Boolean =
        session.lastActivityEpochSeconds > preferences.getLong(key(session.hostId, session.name), 0L)

    fun markRead(session: TmuxSession) {
        val readAt = maxOf(session.lastActivityEpochSeconds, System.currentTimeMillis() / 1000)
        preferences.edit().putLong(key(session.hostId, session.name), readAt).apply()
    }

    fun removeHost(hostId: String) {
        val prefix = "$hostId::"
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        editor.apply()
    }

    companion object {
        fun key(hostId: String, sessionName: String): String = "$hostId::$sessionName"
    }
}
