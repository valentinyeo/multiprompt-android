package dev.multiprompt.companion.dictation

import android.content.Context

internal class PreferencesDictationUsageStore(context: Context) : DictationUsageLog.Store {
    private val preferences = context.getSharedPreferences("dictation_usage", Context.MODE_PRIVATE)

    override fun entries(): Map<String, Double> = preferences.all.mapNotNull { (key, value) ->
        (value as? String)?.toDoubleOrNull()?.let { key to it }
    }.toMap()

    override fun put(key: String, seconds: Double) {
        preferences.edit().putString(key, seconds.toString()).apply()
    }

    override fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }
}
