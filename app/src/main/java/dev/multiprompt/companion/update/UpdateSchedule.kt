package dev.multiprompt.companion.update

object UpdateSchedule {
    const val CHECK_INTERVAL_MS = 15 * 60 * 1000L

    fun shouldCheckNow(lastCheckMillis: Long, nowMillis: Long, intervalMillis: Long = CHECK_INTERVAL_MS): Boolean {
        if (lastCheckMillis <= 0L) return true
        if (nowMillis < lastCheckMillis) return true
        return nowMillis - lastCheckMillis >= intervalMillis
    }
}
