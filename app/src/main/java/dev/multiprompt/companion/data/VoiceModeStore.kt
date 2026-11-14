package dev.multiprompt.companion.data

import android.content.Context

class VoiceModeStore(context: Context) {
    private val preferences = context.getSharedPreferences("voice_mode", Context.MODE_PRIVATE)

    fun enabled(): Boolean = preferences.getBoolean(ENABLED, false)

    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean(ENABLED, value).apply()
    }

    private companion object {
        const val ENABLED = "enabled"
    }
}
