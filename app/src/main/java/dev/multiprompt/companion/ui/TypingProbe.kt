package dev.multiprompt.companion.ui

import dev.multiprompt.companion.BuildConfig

internal object TypingProbe {
    var enabled = BuildConfig.DEBUG
    var compositions: ((String) -> Unit)? = null
    var layouts: ((String) -> Unit)? = null
    var timings: ((String, Long) -> Unit)? = null
    var edits = 0L
        private set

    fun edited() {
        if (enabled) edits++
    }

    fun composed(name: String) {
        if (enabled) compositions?.invoke(name)
    }

    fun laidOut(name: String) {
        if (enabled) layouts?.invoke(name)
    }

    inline fun <T> measure(name: String, block: () -> T): T {
        if (!enabled || timings == null) return block()
        val start = android.os.Debug.threadCpuTimeNanos()
        return try { block() } finally { timings?.invoke(name, android.os.Debug.threadCpuTimeNanos() - start) }
    }
}
