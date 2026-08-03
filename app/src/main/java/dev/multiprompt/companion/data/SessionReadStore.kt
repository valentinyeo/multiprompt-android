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
        archiveUntil(session, null)
    }

    fun archiveUntil(session: TmuxSession, resumeAtEpochSeconds: Long?) {
        val archivedAt = maxOf(session.lastActivityEpochSeconds, System.currentTimeMillis() / 1000)
        val editor = preferences.edit()
            .putLong(key(session.hostId, session.name), archivedAt)
            .putLong(archiveKey(session.hostId, session.name), archivedAt)
        if (resumeAtEpochSeconds == null) {
            editor.remove(archiveUntilKey(session.hostId, session.name))
        } else {
            editor.putLong(archiveUntilKey(session.hostId, session.name), resumeAtEpochSeconds)
        }
        editor.apply()
    }

    fun isArchived(session: TmuxSession): Boolean {
        val archiveKey = archiveKey(session.hostId, session.name)
        if (!preferences.contains(archiveKey)) return false
        val archivedAt = preferences.getLong(archiveKey, Long.MAX_VALUE)
        val resumeAt = preferences.getLong(archiveUntilKey(session.hostId, session.name), 0L)
            .takeIf { it > 0 }
        if (shouldRemainArchived(
                lastActivityEpochSeconds = session.lastActivityEpochSeconds,
                archivedAtEpochSeconds = archivedAt,
                resumeAtEpochSeconds = resumeAt,
                nowEpochSeconds = System.currentTimeMillis() / 1000,
            )
        ) return true
        restore(session)
        return false
    }

    fun restore(session: TmuxSession) {
        preferences.edit()
            .remove(archiveKey(session.hostId, session.name))
            .remove(archiveUntilKey(session.hostId, session.name))
            .apply()
    }

    fun fontScale(session: TmuxSession): Float = normalizeFontScale(
        preferences.getFloat(fontScaleKey(session.hostId, session.name), DEFAULT_FONT_SCALE),
    )

    fun setFontScale(session: TmuxSession, scale: Float) {
        preferences.edit()
            .putFloat(fontScaleKey(session.hostId, session.name), normalizeFontScale(scale))
            .apply()
    }

    fun removeHost(hostId: String) {
        val prefix = "$hostId::"
        val editor = preferences.edit()
        preferences.all.keys
            .filter {
                it.startsWith(prefix) ||
                    it.startsWith("$ARCHIVE_PREFIX$prefix") ||
                    it.startsWith("$ARCHIVE_UNTIL_PREFIX$prefix") ||
                    it.startsWith("$FONT_SCALE_PREFIX$prefix")
            }
            .forEach(editor::remove)
        editor.apply()
    }

    companion object {
        private const val ARCHIVE_PREFIX = "archive::"
        private const val ARCHIVE_UNTIL_PREFIX = "archive_until::"
        private const val FONT_SCALE_PREFIX = "font_scale::"
        private const val DEFAULT_FONT_SCALE = 1.4f
        private const val MIN_FONT_SCALE = 0.75f
        private const val MAX_FONT_SCALE = 5f

        fun key(hostId: String, sessionName: String): String = "$hostId::$sessionName"

        private fun archiveKey(hostId: String, sessionName: String): String =
            "$ARCHIVE_PREFIX${key(hostId, sessionName)}"

        private fun archiveUntilKey(hostId: String, sessionName: String): String =
            "$ARCHIVE_UNTIL_PREFIX${key(hostId, sessionName)}"

        private fun fontScaleKey(hostId: String, sessionName: String): String =
            "$FONT_SCALE_PREFIX${key(hostId, sessionName)}"

        internal fun normalizeFontScale(scale: Float): Float =
            if (scale.isFinite()) scale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE) else DEFAULT_FONT_SCALE

        internal fun shouldRemainArchived(
            lastActivityEpochSeconds: Long,
            archivedAtEpochSeconds: Long,
            resumeAtEpochSeconds: Long?,
            nowEpochSeconds: Long,
        ): Boolean = lastActivityEpochSeconds <= archivedAtEpochSeconds &&
            (resumeAtEpochSeconds == null || nowEpochSeconds < resumeAtEpochSeconds)
    }
}
