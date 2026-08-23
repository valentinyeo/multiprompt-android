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

    fun lastInteractedAt(session: TmuxSession): Long =
        preferences.getLong(interactionKey(session.hostId, session.name), 0L)

    fun markInteracted(session: TmuxSession, atEpochSeconds: Long = System.currentTimeMillis() / 1000) {
        preferences.edit()
            .putLong(interactionKey(session.hostId, session.name), atEpochSeconds)
            .apply()
    }

    /** Hides a session while the agent is still working; it returns when input is available. */
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

    fun isArchived(session: TmuxSession, needsAttention: Boolean): Boolean {
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
                needsAttention = needsAttention,
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
        preferences.getFloat(
            manualFontScaleKey(session.hostId, session.name),
            readerDefaultFontScale(),
        ),
    )

    fun setFontScale(session: TmuxSession, scale: Float) {
        preferences.edit()
            .putFloat(manualFontScaleKey(session.hostId, session.name), normalizeFontScale(scale))
            .apply()
    }

    fun readerDefaultFontScale(): Float = normalizeFontScale(
        preferences.getFloat(READER_DEFAULT_FONT_SCALE, DEFAULT_FONT_SCALE),
    )

    fun setReaderDefaultFontScale(scale: Float) {
        preferences.edit()
            .putFloat(READER_DEFAULT_FONT_SCALE, normalizeFontScale(scale))
            .apply()
    }

    fun readerTechnicalMode(): Boolean =
        preferences.getBoolean(READER_TECHNICAL_MODE, false)

    fun setReaderTechnicalMode(enabled: Boolean) {
        preferences.edit().putBoolean(READER_TECHNICAL_MODE, enabled).apply()
    }

    fun sunlightMode(): Boolean =
        preferences.getBoolean(SUNLIGHT_MODE, false)

    fun setSunlightMode(enabled: Boolean) {
        preferences.edit().putBoolean(SUNLIGHT_MODE, enabled).apply()
    }

    fun clearFontScale(session: TmuxSession) {
        preferences.edit().remove(manualFontScaleKey(session.hostId, session.name)).apply()
    }

    fun newestSessionsAtBottom(): Boolean =
        preferences.getBoolean(NEWEST_SESSIONS_AT_BOTTOM, true)

    fun setNewestSessionsAtBottom(enabled: Boolean) {
        preferences.edit().putBoolean(NEWEST_SESSIONS_AT_BOTTOM, enabled).apply()
    }

    fun allSplitOnRight(): Boolean = preferences.getBoolean(ALL_SPLIT_ON_RIGHT, true)

    fun setAllSplitOnRight(enabled: Boolean) {
        preferences.edit().putBoolean(ALL_SPLIT_ON_RIGHT, enabled).apply()
    }

    fun displayName(session: TmuxSession): String? =
        preferences.getString(displayNameKey(session.hostId, session.name), null)
            ?.takeIf(String::isNotBlank)

    fun setDisplayName(session: TmuxSession, name: String) {
        preferences.edit()
            .putString(displayNameKey(session.hostId, session.name), name)
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
                    it.startsWith("$FONT_SCALE_PREFIX$prefix") ||
                    it.startsWith("$MANUAL_FONT_SCALE_PREFIX$prefix") ||
                    it.startsWith("$DISPLAY_NAME_PREFIX$prefix")
                    || it.startsWith("$INTERACTION_PREFIX$prefix")
            }
            .forEach(editor::remove)
        editor.apply()
    }

    companion object {
        private const val ARCHIVE_PREFIX = "archive::"
        private const val ARCHIVE_UNTIL_PREFIX = "archive_until::"
        private const val FONT_SCALE_PREFIX = "font_scale::"
        private const val MANUAL_FONT_SCALE_PREFIX = "manual_font_scale::"
        private const val READER_DEFAULT_FONT_SCALE = "reader_default_font_scale"
        private const val READER_TECHNICAL_MODE = "reader_technical_mode"
        private const val SUNLIGHT_MODE = "sunlight_mode"
        private const val NEWEST_SESSIONS_AT_BOTTOM = "newest_sessions_at_bottom"
        private const val ALL_SPLIT_ON_RIGHT = "all_split_on_right"
        private const val DISPLAY_NAME_PREFIX = "display_name::"
        private const val INTERACTION_PREFIX = "interaction::"
        private const val DEFAULT_FONT_SCALE = 1f
        private const val MIN_FONT_SCALE = 0.75f
        private const val MAX_FONT_SCALE = 5f

        fun key(hostId: String, sessionName: String): String = "$hostId::$sessionName"

        private fun archiveKey(hostId: String, sessionName: String): String =
            "$ARCHIVE_PREFIX${key(hostId, sessionName)}"

        private fun archiveUntilKey(hostId: String, sessionName: String): String =
            "$ARCHIVE_UNTIL_PREFIX${key(hostId, sessionName)}"

        private fun manualFontScaleKey(hostId: String, sessionName: String): String =
            "$MANUAL_FONT_SCALE_PREFIX${key(hostId, sessionName)}"

        private fun displayNameKey(hostId: String, sessionName: String): String =
            "$DISPLAY_NAME_PREFIX${key(hostId, sessionName)}"

        private fun interactionKey(hostId: String, sessionName: String): String =
            "$INTERACTION_PREFIX${key(hostId, sessionName)}"

        internal fun normalizeFontScale(scale: Float): Float =
            if (scale.isFinite()) scale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE) else DEFAULT_FONT_SCALE

        internal fun shouldRemainArchived(
            lastActivityEpochSeconds: Long,
            archivedAtEpochSeconds: Long,
            resumeAtEpochSeconds: Long?,
            nowEpochSeconds: Long,
            needsAttention: Boolean,
        ): Boolean = if (resumeAtEpochSeconds != null) {
            nowEpochSeconds < resumeAtEpochSeconds
        } else {
            // tmux's session_activity is not guaranteed to advance for agent output. The
            // prompt state is the authoritative signal for the temporary Waiting bucket.
            !needsAttention
        }
    }
}
