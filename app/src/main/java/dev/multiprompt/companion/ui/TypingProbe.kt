package dev.multiprompt.companion.ui

import dev.multiprompt.companion.BuildConfig

internal object TypingProbe {
    var compositions: ((String) -> Unit)? = null
    var timings: ((String, Long) -> Unit)? = null

    fun composed(name: String) {
        if (BuildConfig.DEBUG) compositions?.invoke(name)
    }

    inline fun <T> measure(name: String, block: () -> T): T {
        if (!BuildConfig.DEBUG || timings == null) return block()
        val start = android.os.Debug.threadCpuTimeNanos()
        return try { block() } finally { timings?.invoke(name, android.os.Debug.threadCpuTimeNanos() - start) }
    }
}
