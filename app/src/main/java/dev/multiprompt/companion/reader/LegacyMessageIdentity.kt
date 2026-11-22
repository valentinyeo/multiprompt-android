package dev.multiprompt.companion.reader

internal object LegacyMessageIdentity {
    fun same(left: String, right: String): Boolean =
        left.replace("\r", "").trimEnd(' ') == right.replace("\r", "").trimEnd(' ')

    fun seconds(timestamp: String): Double? =
        runCatching { java.time.Instant.parse(timestamp).toEpochMilli() / 1000.0 }
            .recoverCatching {
                java.time.LocalDateTime.parse(timestamp).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() / 1000.0
            }
            .getOrNull()

    fun shared(message: ChatHistoryMessage, card: MessageProtocol.Row): Boolean =
        message.role == "user" && seconds(message.timestamp)?.let { it >= card.created - 2.0 } == true && same(message.text, card.text)

    fun delivered(message: ChatHistoryMessage, prompt: QueuedMessages.SentPrompt, sid: String, inode: Long): Boolean =
        prompt.sid.isNotEmpty() && prompt.sid == sid && prompt.inode == inode && message.role == "user" &&
            message.offset >= prompt.afterOffset && same(message.text, prompt.text)

    fun replyBoundary(message: ChatHistoryMessage): Boolean = message.role == "user" ||
        message.role == "assistant" && message.requestId.startsWith("history-") &&
            (message.text == "---\nFresh start\n---" || message.text.startsWith("---\nSwitched AI: ") && message.text.endsWith("\n---"))

    fun reply(message: ChatHistoryMessage, block: HostBlock, owner: ChatHistoryMessage?): Boolean =
        block.done && message.role == "assistant" && same(message.text, block.text) &&
            (block.messageId == null || message.requestId.startsWith("history-") && when (owner?.role) {
                "user" -> block.messageId == owner.requestId
                "assistant" -> block.messageId.startsWith("fresh-")
                else -> false
            })
}
