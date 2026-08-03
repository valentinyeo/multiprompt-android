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

    fun markUnread(session: TmuxSession) {
        preferences.edit()
            .putLong(key(session.hostId, session.name), session.lastActivityEpochSeconds - 1)
            .apply()
    }

    /** Hides a session until tmux reports activity newer than this archive action. */
    fun archive(session: TmuxSession) {
        val archivedAt = maxOf(session.lastActivityEpochSeconds, System.currentTimeMillis() / 1000)
        preferences.edit()
            .putLong(key(session.hostId, session.name), archivedAt)
            .putLong(archiveKey(session.hostId, session.name), archivedAt)
            .apply()
    }

    fun isArchived(session: TmuxSession): Boolean {
        val archiveKey = archiveKey(session.hostId, session.name)
        if (!preferences.contains(archiveKey)) return false
        val archivedAt = preferences.getLong(archiveKey, Long.MAX_VALUE)
        if (session.lastActivityEpochSeconds <= archivedAt) return true
        preferences.edit().remove(archiveKey).apply()
        return false
    }

    fun restore(session: TmuxSession) {
        preferences.edit().remove(archiveKey(session.hostId, session.name)).apply()
    }

    fun removeHost(hostId: String) {
        val prefix = "$hostId::"
        val editor = preferences.edit()
        preferences.all.keys
            .filter { it.startsWith(prefix) || it.startsWith("$ARCHIVE_PREFIX$prefix") }
            .forEach(editor::remove)
        editor.apply()
    }

    companion object {
        private const val ARCHIVE_PREFIX = "archive::"

        fun key(hostId: String, sessionName: String): String = "$hostId::$sessionName"

        private fun archiveKey(hostId: String, sessionName: String): String =
            "$ARCHIVE_PREFIX${key(hostId, sessionName)}"
    }
}
