package dev.multiprompt.companion.reader

object QueuedMessages {
    enum class Delivery { SENDING, QUEUED, SUBMITTED, DELIVERED, CANCELLED }

    data class SentPrompt(
        val text: String,
        val sentAtMillis: Long,
        val messageId: String = "",
        val delivery: Delivery = Delivery.SENDING,
        val sid: String = "",
        val inode: Long = 0,
        val afterOffset: Long = 0,
        val localId: String = if (messageId.isEmpty()) java.util.UUID.randomUUID().toString() else "",
    )

}
