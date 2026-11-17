package dev.multiprompt.companion.reader

internal object LegacyMessageIdentity {
    fun same(left: String, right: String): Boolean =
        left.replace("\r", "").trimEnd(' ') == right.replace("\r", "").trimEnd(' ')

    fun seconds(timestamp: String): Double? =
        runCatching { java.time.Instant.parse(timestamp).toEpochMilli() / 1000.0 }.getOrNull()

    fun shared(message: ChatHistoryMessage, card: MessageProtocol.Row): Boolean =
        message.role == "user" && seconds(message.timestamp)?.let { it >= card.created - 2.0 } == true && same(message.text, card.text)

    fun delivered(message: ChatHistoryMessage, prompt: QueuedMessages.SentPrompt, sid: String, inode: Long): Boolean =
        prompt.sid.isNotEmpty() && prompt.sid == sid && prompt.inode == inode && message.role == "user" &&
            message.offset >= prompt.afterOffset && same(message.text, prompt.text)

    fun reply(message: ChatHistoryMessage, block: HostBlock): Boolean =
        block.done && block.messageId == null && message.role == "assistant" && same(message.text, block.text)
}
