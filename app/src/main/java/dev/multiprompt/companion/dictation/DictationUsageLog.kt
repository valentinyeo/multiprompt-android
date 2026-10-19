package dev.multiprompt.companion.dictation

import java.time.LocalDate

class DictationUsageLog(
    private val store: Store,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    interface Store {
        fun entries(): Map<String, Double>
        fun put(key: String, seconds: Double)
        fun remove(key: String)
    }

    @Synchronized
    fun add(seconds: Double): Double {
        require(seconds.isFinite() && seconds >= 0)
        val date = today()
        val entries = store.entries()
        val total = (entries[date.toString()] ?: 0.0) + seconds
        store.put(date.toString(), total)
        val oldest = date.minusDays(RETAINED_DAYS - 1L)
        entries.keys.forEach { key ->
            val day = runCatching { LocalDate.parse(key) }.getOrNull()
            if (day == null || day < oldest || day > date) store.remove(key)
        }
        return total
    }

    companion object {
        const val RETAINED_DAYS = 30
    }
}
