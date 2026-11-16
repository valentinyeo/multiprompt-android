package dev.multiprompt.companion.reader

object ConversationLoad {
    const val TIMEOUT_MS = 10_000L
    const val FAILED = "Could not load the conversation from the host. Retrying…"
    const val TIMED_OUT = "The chat host stopped answering. Retrying…"

    fun expired(now: Long, started: Long, lastReply: Long, ready: Boolean, timeoutMillis: Long): Boolean =
        now - lastReply >= timeoutMillis * 1_000_000L ||
            (!ready && now - started >= timeoutMillis * 1_000_000L)
}
