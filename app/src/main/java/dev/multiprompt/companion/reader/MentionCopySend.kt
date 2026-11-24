package dev.multiprompt.companion.reader

import org.json.JSONObject

object MentionCopySend {
    fun request(id: Long, scope: MessageProtocol.Scope, messageId: String, text: String): String =
        MessageProtocol.request(id, scope, "message_submit", JSONObject().put("message_id", messageId).put("text", text))

    fun accepted(reply: String, id: Long, scope: MessageProtocol.Scope, messageId: String, text: String): Boolean =
        runCatching { MessageProtocol.requiredMutation(reply, id, scope, messageId, text) }.isSuccess

    fun messageId(random: () -> String = { java.util.UUID.randomUUID().toString() }): String =
        random().filter { it.isLetterOrDigit() }.take(32).padEnd(16, '0')
}
