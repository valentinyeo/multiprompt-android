package dev.multiprompt.companion.reader

object ReconnectBackoff {
    const val INITIAL_MS = 2_000L
    const val MAX_MS = 30_000L

    fun afterWait(current: Long, nudged: Boolean): Long =
        if (nudged) INITIAL_MS else (current * 2).coerceAtMost(MAX_MS)

    fun shouldNudge(retrying: Boolean): Boolean = retrying
}

class NetworkNudgeGate(private val windowMs: Long = WINDOW_MS) {
    private var lastNudgeAt: Long? = null
    private var validatedNetwork: Long? = null

    @Synchronized
    fun onNetwork(network: Long, validated: Boolean, nowMs: Long): Boolean {
        if (!validated) {
            if (network == validatedNetwork) validatedNetwork = null
            return false
        }
        if (network == validatedNetwork || insideWindow(nowMs)) return false
        validatedNetwork = network
        lastNudgeAt = nowMs
        return true
    }

    @Synchronized
    fun onResume(nowMs: Long): Boolean {
        if (insideWindow(nowMs)) return false
        lastNudgeAt = nowMs
        return true
    }

    @Synchronized
    fun reset() {
        validatedNetwork = null
    }

    private fun insideWindow(nowMs: Long): Boolean = lastNudgeAt?.let { nowMs - it < windowMs } == true

    companion object {
        const val WINDOW_MS = 2_000L
    }
}
