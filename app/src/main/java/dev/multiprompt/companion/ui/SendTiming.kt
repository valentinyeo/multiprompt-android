package dev.multiprompt.companion.ui

import android.os.SystemClock
import android.util.Log

internal object SendTiming {
    private const val TAG = "SendTiming"

    @Volatile
    private var tapAt = 0L

    fun tap() {
        tapAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "tap")
    }

    fun mark(name: String) {
        Log.i(TAG, "$name +${SystemClock.elapsedRealtime() - tapAt}ms")
    }
}
