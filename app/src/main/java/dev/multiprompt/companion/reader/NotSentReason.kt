package dev.multiprompt.companion.reader

import org.json.JSONObject

object NotSentReason {
    private const val GENERIC = "Could not send. Try again."

    private val PLAIN = mapOf(
        "draft_present" to "Chat was busy typing, try again",
        "draft_not_cleared" to "Chat still had a draft, try again",
        "input_not_submitted" to "Chat did not accept the message, try again",
        "session_changed" to "Chat closed",
        "prompt_unavailable" to "Chat input was not ready, try again",
        "buffer_unavailable" to "Could not reach the chat, try again",
        "invalid_tmux_name" to "Chat was unavailable, try again",
        "invalid_identity_or_text" to "Message could not be sent, try again",
        "waiting_too_long" to "Chat took too long to accept this, try again",
        "connector_unavailable" to "Send is not available on this chat, try again",
        "host_closed" to "Chat closed",
        "host_unreachable" to "Could not reach the chat computer, try again",
        "host_not_running" to "Chat is not running on the computer, try again",
        "host_disabled" to "Chat is turned off on the computer",
        "host_handed_over" to "Chat is in the terminal, try again from the computer",
        "owner_busy" to "Chat was busy, try again",
        "not_sent" to GENERIC,
    )

    fun plainText(receipt: String?): String? {
        val code = reasonCode(receipt) ?: return if (receipt == "not_sent") GENERIC else null
        return PLAIN[code] ?: GENERIC
    }

    fun reasonCode(receipt: String?): String? {
        if (receipt.isNullOrBlank()) return null
        val trimmed = receipt.trim()
        if (trimmed.startsWith("host_")) return trimmed.removePrefix("host_")
        if (trimmed.startsWith("{")) {
            return runCatching { JSONObject(trimmed).optString("reason").takeIf { it.isNotBlank() } }.getOrNull()
        }
        return trimmed
    }
}