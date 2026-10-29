package dev.multiprompt.companion.ui

import android.os.SystemClock
import android.util.Log

internal object SendTiming {
    private const val TAG = "SendTiming"

    @Volatile
    private var tapAt = 0L

    fun tap() {
        android.os.Debug.startMethodTracingSampling("/data/user/0/dev.multiprompt.companion.debug/cache/send.trace", 8 * 1024 * 1024, 1000)
        tapAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "tap")
    }

    fun mark(name: String) {
        Log.i(TAG, "$name +${SystemClock.elapsedRealtime() - tapAt}ms")
        if (name == "box_cleared_frame") android.os.Debug.stopMethodTracing()
    }
}
