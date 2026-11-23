package dev.multiprompt.companion.reader

@JvmInline
internal value class MessageText(val value: String)

internal object MessageIdentity {
    fun text(raw: String): MessageText = MessageText(raw)

    fun key(sid: String, inode: Long, role: String, identity: String): String = "$sid:$inode:$role:$identity"

    fun shared(id: String): String = "@shared:$id"

    fun held(id: String): String = "@held:$id"

    fun local(localId: String): String = "@local:$localId"

    fun live(turn: String?, item: String): String = "@live:$turn:$item"

    fun unknown(id: String): String = "@unknown:$id"

    fun unsaved(key: String): Boolean =
        key.startsWith("@local:") || key.startsWith("@shared:") || key.startsWith("@unknown:")

    fun unread(role: String, timestamp: String, text: String): String = "transcript:$role:$timestamp:${text.hashCode()}"

    fun unread(block: String): String = "block:${block.hashCode()}"

    fun same(left: MessageText, right: MessageText): Boolean = LegacyMessageIdentity.same(left, right)

    fun sameKnown(left: MessageText?, right: MessageText): Boolean = left != null && same(left, right)

    fun sameOrUnrecorded(recorded: String?, observed: MessageText): Boolean =
        recorded == null || same(text(recorded), observed)

    fun seconds(timestamp: String): Double? = LegacyMessageIdentity.seconds(timestamp)

    fun shared(message: ChatHistoryMessage, card: MessageProtocol.Row): Boolean =
        LegacyMessageIdentity.shared(message, card)

    fun delivered(message: ChatHistoryMessage, prompt: QueuedMessages.SentPrompt, sid: String, inode: Long): Boolean =
        LegacyMessageIdentity.delivered(message, prompt, sid, inode)

    fun replyBoundary(message: ChatHistoryMessage): Boolean = LegacyMessageIdentity.replyBoundary(message)

    fun reply(message: ChatHistoryMessage, block: HostBlock, owner: ChatHistoryMessage?): Boolean =
        LegacyMessageIdentity.reply(message, block, owner)
}

internal val ChatHistoryMessage.identityText: MessageText get() = MessageText(text)

internal val MessageProtocol.Row.identityText: MessageText get() = MessageText(text)

internal val MessageJournal.Entry.identityText: MessageText get() = MessageText(text.orEmpty())

internal val PendingPrompts.Entry.identityText: MessageText get() = MessageText(text)
